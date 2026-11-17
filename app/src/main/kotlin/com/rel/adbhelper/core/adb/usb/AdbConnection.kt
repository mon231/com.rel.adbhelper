package com.rel.adbhelper.core.adb.usb

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/**
 * The raw byte pipe an [AdbConnection] runs over. All methods block; [close] may be called from
 * any thread and must make a [read] or [write] blocked on another thread fail. Once [close] has
 * been called, every later [read] and [write] throws [IOException]: that is how a reader loop
 * learns the channel is gone.
 */
interface AdbPacketChannel {
    /** Sends all of [bytes] as one transfer. The header and the payload of a packet are passed
     * in separate calls. */
    @Throws(IOException::class)
    fun write(bytes: ByteArray)

    /** Blocks until some data arrives, stores at most [length] bytes at [offset] and returns
     * how many. Returning 0 is allowed and means "nothing this time" (a read timeout, or an
     * empty packet): callers just read again. Never returns a negative number; throws once the
     * channel is closed. */
    @Throws(IOException::class)
    fun read(buffer: ByteArray, offset: Int, length: Int): Int

    fun close()
}

/**
 * One established ADB session over an [AdbPacketChannel]: the handshake ([connect]), then any
 * number of multiplexed streams ([open]).
 *
 * Everything here blocks. One reader thread per connection receives every packet and hands it
 * to the stream it belongs to; packets are sent from whichever thread needs to send one, one
 * whole packet at a time.
 */
class AdbConnection private constructor(
    private val channel: AdbPacketChannel,
    /** The largest payload the device accepts in one packet. */
    val peerMaxPayload: Int,
    /** The device's CNXN banner, e.g. `device::ro.product.name=…;features=…`. */
    val peerBanner: String,
    /** Receives one line when the connection dies (the first cause only). */
    private val log: (String) -> Unit = ::androidWarn,
) {
    private val streams = ConcurrentHashMap<Int, AdbConnectionStream>()
    private val nextLocalId = AtomicInteger(1)
    private val writeLock = Any()
    private val failureLock = Any()
    private val sentPackets = AtomicLong()
    private val sentBytes = AtomicLong()
    private val receivedPackets = AtomicLong()
    private val receivedBytes = AtomicLong()

    /** When the reader last finished a packet, and when the write now in progress (0 = none)
     * began, both in [System.nanoTime] units: they say, when the connection dies, whether it
     * was the reading or the writing side that had stopped moving. */
    @Volatile private var lastReadNanos = System.nanoTime()
    @Volatile private var writeStartedNanos = 0L

    /** (our id, device id) pairs already told to close because the device kept talking on them. */
    private val rejectedStreams = ConcurrentHashMap.newKeySet<Long>()

    /** Sends the CLSE of streams closed locally — off the caller's thread, because
     * [AdbConnectionStream.close] must never block (it is called from UI and watchdog code). */
    private val closeSender: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "adb-usb-close").apply { isDaemon = true }
    }

    @Volatile
    private var failure: IOException? = null

    /** False once the connection has died or been closed; it never comes back. */
    val isAlive: Boolean get() = failure == null

    /** Why the connection died, e.g. `reader: Connection reset`, or null while it is alive. The
     * first cause wins; later failures (a write on the already closed channel) are consequences. */
    val failureReason: String? get() = failure?.message

    /**
     * Opens a stream to [service] (e.g. `shell:ls`). Blocks until the device accepts or refuses.
     * Safe to call from several threads at once.
     */
    @Throws(IOException::class, InterruptedException::class)
    fun open(service: String): AdbConnectionStream {
        val stream = AdbConnectionStream(this, nextLocalId.getAndIncrement())
        streams[stream.localId] = stream
        try {
            // Checked after registering: fail() marks the connection dead before it walks the
            // stream map, so a stream registered too late to be failed by it is caught here.
            failure?.let { throw lost(it) }
            send(AdbMessage.open(stream.localId, service))
            stream.awaitOpened(service)
        } catch (t: Throwable) {
            // Refused, connection lost, or the caller gave up waiting (interrupt). If the device
            // accepts later after all, the reader answers that OKAY with CLSE (unknown id).
            stream.close()
            throw t
        }
        return stream
    }

    /** Ends the session: closes the channel and fails every open stream. Idempotent. */
    fun close() = fail(IOException("closed by this app"), "close()")

    /** Sends one packet, header and payload as two channel writes, never interleaved with
     * another packet. Any failure leaves the byte stream in an unknown state, so it kills the
     * connection. */
    internal fun send(message: AdbMessage) {
        synchronized(writeLock) {
            failure?.let { throw lost(it) }
            try {
                writeStartedNanos = System.nanoTime()
                writeMessage(channel, message)
                writeStartedNanos = 0
                sentPackets.incrementAndGet()
                sentBytes.addAndGet(message.payload.size.toLong())
            } catch (e: IOException) {
                fail(e, "writing ${AdbMessage.commandName(message.command)}")
                // Report the reason the connection died, which may be an earlier one (the
                // reader's) that closed the channel under this write.
                throw lost(failure ?: e)
            }
        }
    }

    internal fun forget(stream: AdbConnectionStream) {
        streams.remove(stream.localId, stream)
    }

    internal fun sendCloseAsync(localId: Int, remoteId: Int) {
        try {
            closeSender.execute {
                try {
                    send(AdbMessage.close(localId, remoteId))
                } catch (_: IOException) {
                    // The connection is gone; so is the remote end of the stream.
                }
            }
        } catch (_: RejectedExecutionException) {
            // Connection already shut down.
        }
    }

    private fun lost(reason: IOException) = IOException("ADB connection lost (${reason.message})", reason)

    private fun fail(rawCause: IOException, origin: String) {
        val cause = IOException("$origin: ${rawCause.message ?: rawCause.javaClass.simpleName}", rawCause)
        synchronized(failureLock) {
            if (failure != null) return
            failure = cause
        }
        val now = System.nanoTime()
        val writing = writeStartedNanos.let { if (it == 0L) "no write in progress" else "a write has been blocked for ${(now - it) / 1_000_000} ms" }
        log("connection died, $origin on thread ${Thread.currentThread().name}: $rawCause (${streams.size} open streams ${streams.keys.sorted()}; " +
            "sent $sentPackets packets/$sentBytes payload bytes, received $receivedPackets packets/$receivedBytes; " +
            "$writing; reader last got a packet ${(now - lastReadNanos) / 1_000_000} ms ago)")
        // Not under writeLock: a thread stuck inside channel.write holds it, and closing the
        // channel is exactly what gets that thread out.
        runCatching { channel.close() }
        closeSender.shutdownNow()
        streams.values.forEach { it.fail(lost(cause)) }
        streams.clear()
    }

    private fun startReader() {
        thread(name = "adb-usb-reader", isDaemon = true) {
            try {
                while (true) {
                    val message = readMessage(channel)
                    receivedPackets.incrementAndGet()
                    receivedBytes.addAndGet(message.payload.size.toLong())
                    lastReadNanos = System.nanoTime()
                    dispatch(message)
                }
            } catch (e: IOException) {
                fail(e, "reader")
            } catch (t: Throwable) {
                fail(IOException("ADB reader failed: $t", t), "reader")
            }
        }
    }

    private fun dispatch(message: AdbMessage) {
        // In every stream packet arg0 is the sender's id for the stream and arg1 is ours.
        val remoteId = message.arg0
        val localId = message.arg1
        when (message.command) {
            AdbWire.OKAY -> {
                val stream = streams[localId]
                if (stream != null) stream.onOkay(remoteId) else rejectUnknownStream(localId, remoteId)
            }
            AdbWire.WRTE -> {
                val stream = streams[localId]
                when {
                    stream == null -> rejectUnknownStream(localId, remoteId)
                    // Nothing to queue, so nothing would ever be consumed: acknowledge right away.
                    message.payload.isEmpty() -> sendFromReader(AdbMessage.okay(localId, remoteId))
                    else -> stream.onData(message.payload)
                }
            }
            AdbWire.CLSE -> streams.remove(localId)?.onRemoteClose()
            AdbWire.CNXN, AdbWire.AUTH ->
                // The device started a new session (adbd restarted): every stream id is void.
                throw AdbProtocolException("Device restarted the ADB session (${AdbMessage.commandName(message.command)})")
            else -> Unit // Unknown or irrelevant command: the protocol says to ignore it.
        }
    }

    /** A packet for a stream we no longer have (closed locally, or an OPEN we gave up on): tell
     * the device to drop its end, once per stream however much more it sends. Never done in
     * reply to a CLSE, so this cannot ping-pong. */
    private fun rejectUnknownStream(localId: Int, remoteId: Int) {
        if (remoteId == 0 || !rejectedStreams.add((localId.toLong() shl 32) or (remoteId.toLong() and 0xffffffffL))) return
        sendFromReader(AdbMessage.close(localId, remoteId))
    }

    /**
     * The reader must never wait for a write: a write blocks when the device is not reading, the
     * device may not be reading because it is blocked writing to us, and only the reader drains
     * that. So what the reader has to answer is queued to the sender thread instead.
     */
    private fun sendFromReader(message: AdbMessage) {
        try {
            closeSender.execute {
                try {
                    send(message)
                } catch (_: IOException) {
                    // The connection is gone.
                }
            }
        } catch (_: RejectedExecutionException) {
            // Connection already shut down.
        }
    }

    companion object {
        /** How many packets that are neither CNXN nor AUTH are tolerated during the handshake
         * (left-overs of an earlier session the device had queued) before giving up. */
        private const val MAX_STRAY_HANDSHAKE_PACKETS = 64

        /**
         * Performs the ADB handshake on [channel] and returns the live connection. Blocks; if
         * the device does not know [identity]'s key yet it blocks until the user accepts the
         * authorisation prompt on the device — [onAwaitingAuthorisation] is called (on this
         * thread) when that wait begins. Closing [channel] from another thread aborts it.
         * On any failure the channel is closed.
         */
        @Throws(IOException::class)
        fun connect(
            channel: AdbPacketChannel,
            identity: AdbIdentity,
            onAwaitingAuthorisation: () -> Unit = {},
            log: (String) -> Unit = ::androidWarn,
        ): AdbConnection {
            try {
                writeMessage(channel, AdbMessage.connect())
                var signatureSent = false
                var strayPackets = 0
                while (true) {
                    val message = readMessage(channel)
                    when (message.command) {
                        AdbWire.CNXN -> {
                            val connection = AdbConnection(
                                channel,
                                negotiatedMaxPayload(message.arg1),
                                String(message.payload, Charsets.UTF_8).trimEnd('\u0000'),
                                log,
                            )
                            connection.startReader()
                            return connection
                        }
                        AdbWire.AUTH -> {
                            if (message.arg0 != AdbWire.AUTH_TOKEN) {
                                throw AdbProtocolException("Unexpected AUTH type ${message.arg0} from the device")
                            }
                            if (!signatureSent) {
                                signatureSent = true
                                writeMessage(channel, AdbMessage.authSignature(identity.sign(message.payload)))
                            } else {
                                // Our signature was not accepted: the device does not know this
                                // key. Offer it; the device now asks its user.
                                writeMessage(channel, AdbMessage.authPublicKey(identity.publicKeyPayload()))
                                onAwaitingAuthorisation()
                            }
                        }
                        else -> if (++strayPackets > MAX_STRAY_HANDSHAKE_PACKETS) {
                            throw AdbProtocolException("Device did not answer the ADB handshake")
                        }
                    }
                }
            } catch (t: Throwable) {
                runCatching { channel.close() }
                throw t as? IOException ?: IOException("ADB handshake failed", t)
            }
        }

        private fun negotiatedMaxPayload(advertised: Int): Int {
            if (advertised == 0) throw AdbProtocolException("Device advertised a maximum payload of 0")
            // Unsigned on the wire; anything above what we can send in one piece is capped.
            return minOf(advertised.toUInt(), AdbWire.MAX_PAYLOAD.toUInt()).toInt()
        }

        private fun writeMessage(channel: AdbPacketChannel, message: AdbMessage) {
            channel.write(message.encodeHeader())
            if (message.payload.isNotEmpty()) channel.write(message.payload)
        }

        private fun readMessage(channel: AdbPacketChannel): AdbMessage {
            val headerBytes = ByteArray(AdbWire.HEADER_LENGTH)
            readFully(channel, headerBytes)
            val header = AdbHeader.decode(headerBytes)
            val payload = ByteArray(header.dataLength)
            readFully(channel, payload)
            return header.toMessage(payload)
        }

        private fun readFully(channel: AdbPacketChannel, buffer: ByteArray) {
            var offset = 0
            // A read that returns 0 only means "nothing arrived within the channel's read
            // timeout" (or a zero-length packet), which is routine on an idle connection. The
            // loop ends when the channel delivers the bytes or, once it has been closed, throws.
            while (offset < buffer.size) offset += channel.read(buffer, offset, buffer.size - offset)
        }
    }
}

/**
 * One stream of an [AdbConnection]. Blocking; see `ConnectionAdbStream` for the suspending
 * adapter the rest of the app uses.
 *
 * Flow control follows the protocol in both directions: after a WRTE we wait for the device's
 * OKAY before sending the next one, and the device's WRTE is acknowledged with OKAY only once
 * its payload has been taken by [readChunk] — so a slow reader holds the device back instead of
 * letting data pile up here without bound.
 */
class AdbConnectionStream internal constructor(
    private val connection: AdbConnection,
    internal val localId: Int,
) {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()

    // All guarded by lock.
    private var remoteId = 0
    private var remoteClosed = false
    private var localClosed = false
    private var failure: IOException? = null
    /** True while the device is ready for our next WRTE: after its OKAY, until we send one. */
    private var writeReady = false
    private val incoming = ArrayDeque<ByteArray>()

    /**
     * The next chunk of data from the device, blocking until there is one. Returns null once the
     * device has closed the stream and everything it sent has been returned.
     *
     * @throws IOException with the message `Stream closed.` if [close] was (or is, while this
     * call is blocked) called on this stream; any other [IOException] if the connection died.
     */
    @Throws(IOException::class, InterruptedException::class)
    fun readChunk(): ByteArray? {
        val chunk: ByteArray
        val acknowledgeTo: Int
        lock.withLock {
            while (true) {
                if (localClosed) throw IOException(STREAM_CLOSED)
                val next = incoming.removeFirstOrNull()
                if (next != null) {
                    chunk = next
                    acknowledgeTo = if (remoteClosed || failure != null) 0 else remoteId
                    break
                }
                if (remoteClosed) return null
                failure?.let { throw it }
                changed.await()
            }
        }
        if (acknowledgeTo != 0) {
            try {
                connection.send(AdbMessage.okay(localId, acknowledgeTo))
            } catch (_: IOException) {
                // The chunk itself arrived intact; the dead connection surfaces on the next call.
            }
        }
        return chunk
    }

    /**
     * Sends [data], split into packets no larger than the device accepts, and returns once the
     * device has acknowledged the last of them.
     */
    @Throws(IOException::class, InterruptedException::class)
    fun write(data: ByteArray) {
        val maxPayload = connection.peerMaxPayload
        var offset = 0
        while (offset < data.size) {
            val length = minOf(maxPayload, data.size - offset)
            val target = lock.withLock {
                while (true) {
                    checkOpenForWriting()
                    if (writeReady) break
                    changed.await()
                }
                writeReady = false
                remoteId
            }
            connection.send(AdbMessage.write(localId, target, data.copyOfRange(offset, offset + length)))
            offset += length
        }
        lock.withLock {
            // Acknowledged-then-closed still counts as delivered, so readiness is checked first.
            while (!writeReady) {
                checkOpenForWriting()
                changed.await()
            }
        }
    }

    /**
     * Closes the stream from our side. Never blocks: the CLSE packet is sent in the background.
     * A [readChunk] or [write] blocked on another thread fails with `Stream closed.`. Idempotent.
     */
    fun close() {
        val notifyRemoteId = lock.withLock {
            if (localClosed) return
            localClosed = true
            incoming.clear()
            changed.signalAll()
            if (remoteClosed || failure != null) 0 else remoteId
        }
        connection.forget(this)
        if (notifyRemoteId != 0) connection.sendCloseAsync(localId, notifyRemoteId)
    }

    internal fun awaitOpened(service: String) {
        lock.withLock {
            while (remoteId == 0) {
                if (remoteClosed) throw IOException("The device refused to open $service")
                failure?.let { throw IOException("${it.message} while opening $service", it) }
                if (localClosed) throw IOException(STREAM_CLOSED)
                changed.await()
            }
        }
    }

    internal fun onOkay(fromRemoteId: Int) {
        lock.withLock {
            if (fromRemoteId == 0) return
            if (remoteId == 0) remoteId = fromRemoteId else if (remoteId != fromRemoteId) return
            writeReady = true
            changed.signalAll()
        }
    }

    internal fun onData(payload: ByteArray) {
        lock.withLock {
            if (localClosed) return
            incoming.addLast(payload)
            changed.signalAll()
        }
    }

    internal fun onRemoteClose() {
        lock.withLock {
            remoteClosed = true
            changed.signalAll()
        }
    }

    internal fun fail(cause: IOException) {
        lock.withLock {
            if (failure == null) failure = cause
            changed.signalAll()
        }
    }

    private fun checkOpenForWriting() {
        if (localClosed) throw IOException(STREAM_CLOSED)
        if (remoteClosed) throw IOException("The device closed the stream")
        failure?.let { throw it }
    }

    private companion object {
        /** Exact text `AdbClient.raw` matches on; do not reword. */
        const val STREAM_CLOSED = "Stream closed."
    }
}

/** android.util.Log, unless this runs on the plain JVM (unit tests), where it is not available. */
internal fun androidWarn(message: String) {
    try {
        android.util.Log.w("AdbConnection", message)
    } catch (_: RuntimeException) {
        System.err.println("AdbConnection: $message")
    }
}

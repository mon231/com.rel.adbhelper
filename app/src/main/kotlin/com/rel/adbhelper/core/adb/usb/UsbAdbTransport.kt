package com.rel.adbhelper.core.adb.usb

import com.rel.adbhelper.core.adb.AdbStream
import com.rel.adbhelper.core.adb.AdbTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [AdbTransport] over one ADB-over-USB connection, shared by every stream (the protocol
 * multiplexes them). The connection is made lazily by the first [open] (or by [connect]),
 * reused while it is alive and re-made after it died or after [invalidate].
 *
 * [openChannel] is called once per connection attempt and must return a fresh channel each
 * time. The attempt runs in this transport's own scope, so a caller that gives up waiting
 * (cancelled, timed out) neither aborts it nor starts a second one next to it; a handshake
 * that waits for the user to accept the device's prompt is ended by [close]. Every other
 * handshake is bounded by [handshakeTimeoutMillis], so an attempt can never stay pending forever.
 */
class UsbAdbTransport(
    private val openChannel: () -> AdbPacketChannel,
    private val identity: AdbIdentity,
    private val onAwaitingAuthorisation: () -> Unit = {},
    /** How long a handshake may take before the device starts waiting for the user. A device
     * that has not answered by then is not going to (a connection that went stale, a device
     * that is busy booting): the attempt is ended so the next [open] starts a fresh one. */
    private val handshakeTimeoutMillis: Long = 10_000,
) : AdbTransport {

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // All guarded by lock.
    private var connection: AdbConnection? = null
    private var pending: Deferred<AdbConnection>? = null
    private var attempt: AdbPacketChannel? = null
    private var closed = false

    override suspend fun open(service: String): AdbStream {
        val connection = connect()
        return ConnectionAdbStream(runInterruptible(Dispatchers.IO) { connection.open(service) })
    }

    /**
     * Makes sure there is a live connection and returns it. Has no timeout of its own: while the
     * device shows its authorisation prompt this waits for the person to answer it, which is why
     * a caller that wants to wait that long calls this first instead of relying on [open] under
     * a time limit.
     */
    suspend fun connect(): AdbConnection {
        val attempt = synchronized(lock) {
            if (closed) throw IOException("The USB connection was closed")
            connection?.takeIf { it.isAlive }?.let { return it }
            pending?.takeIf { !it.isCompleted } ?: scope.async { establish() }.also { pending = it }
        }
        return attempt.await()
    }

    override fun invalidate() {
        val dropped = synchronized(lock) { connection.also { connection = null } }
        dropped?.close()
    }

    /** Ends everything: the connection, a handshake in progress, and the transport itself. */
    fun close() {
        val (dropped, handshake) = synchronized(lock) {
            closed = true
            Pair(connection.also { connection = null }, attempt.also { attempt = null })
        }
        dropped?.close()
        handshake?.close()
        scope.cancel()
    }

    private fun establish(): AdbConnection {
        val channel = openChannel()
        synchronized(lock) {
            if (closed) {
                channel.close()
                throw IOException("The USB connection was closed")
            }
            attempt = channel
        }
        val waitingForUser = AtomicBoolean(false)
        val settled = AtomicBoolean(false) // whoever sets it first (the handshake or the watchdog) decides
        val timedOut = AtomicBoolean(false)
        val watchdog = scope.launch {
            delay(handshakeTimeoutMillis)
            if (!waitingForUser.get() && settled.compareAndSet(false, true)) {
                timedOut.set(true)
                channel.close()
            }
        }
        val established = try {
            AdbConnection.connect(channel, identity, { waitingForUser.set(true); onAwaitingAuthorisation() }).also {
                if (!settled.compareAndSet(false, true)) {
                    // The watchdog closed the channel just as the handshake finished.
                    it.close()
                    throw IOException("handshake finished as its time limit expired")
                }
            }
        } catch (e: IOException) {
            if (timedOut.get()) {
                throw IOException("The device did not complete the ADB handshake within ${handshakeTimeoutMillis / 1000.0} s", e)
            }
            throw e
        } finally {
            settled.set(true) // (the handshake failed or finished: the watchdog has nothing left to do)
            watchdog.cancel()
            synchronized(lock) { if (attempt === channel) attempt = null }
        }
        synchronized(lock) {
            if (!closed) {
                connection = established
                return established
            }
        }
        established.close()
        throw IOException("The USB connection was closed")
    }
}

/** Adapts a blocking [AdbConnectionStream] to the suspending [AdbStream]. A cancelled caller
 * interrupts the blocked call (which leaves the stream consistent, see [AdbConnectionStream]);
 * [close] never blocks and ends a call blocked on another thread with `Stream closed.`. */
internal class ConnectionAdbStream(private val stream: AdbConnectionStream) : AdbStream {
    override suspend fun write(data: ByteArray) = runInterruptible(Dispatchers.IO) { stream.write(data) }

    override suspend fun readChunk(): ByteArray? = runInterruptible(Dispatchers.IO) { stream.readChunk() }

    override fun close() = stream.close()
}

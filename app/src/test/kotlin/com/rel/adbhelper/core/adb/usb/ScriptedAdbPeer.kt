package com.rel.adbhelper.core.adb.usb

import org.junit.jupiter.api.Assertions.fail
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The device side of an [AdbPacketChannel], driven by a test. The code under test gets this
 * object as its channel; the test thread plays the device: [expect] the next packet the client
 * wrote (parsed with the real codec), [send] a reply.
 *
 * Semantics follow what the Android channel promises: [read] blocks until data arrives and
 * returns 0 when [pollMillis] pass without any; once [close] has been called every [read] and
 * [write] throws. Every wait on the test side has a timeout, so a bug fails instead of hanging.
 */
class ScriptedAdbPeer(private val pollMillis: Long = 50) : AdbPacketChannel {

    private class Failure(val error: IOException)
    private object Closed

    private val toClient = LinkedBlockingQueue<Any>()
    private val fromClient = LinkedBlockingQueue<ByteArray>()
    private var current: ByteArray? = null
    private var currentPosition = 0
    private var received = ByteArray(0) // test thread only

    @Volatile
    var isClosed = false
        private set

    /** How many times [read] returned 0 because nothing arrived in time. */
    val emptyReads = AtomicInteger()

    override fun write(bytes: ByteArray) {
        if (isClosed) throw IOException("channel closed")
        fromClient.put(bytes.copyOf())
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        while (true) {
            if (isClosed) throw IOException("channel closed")
            val chunk = current
            if (chunk != null && currentPosition < chunk.size) {
                val count = minOf(length, chunk.size - currentPosition)
                System.arraycopy(chunk, currentPosition, buffer, offset, count)
                currentPosition += count
                return count
            }
            when (val item = toClient.poll(pollMillis, TimeUnit.MILLISECONDS)) {
                null -> {
                    emptyReads.incrementAndGet()
                    return 0
                }
                is Failure -> throw item.error
                Closed -> throw IOException("channel closed")
                is ByteArray -> {
                    current = item
                    currentPosition = 0
                }
            }
        }
    }

    override fun close() {
        isClosed = true
        toClient.put(Closed)
    }

    // ---- the test's side ----

    fun send(message: AdbMessage) = toClient.put(message.encode())

    fun sendRaw(bytes: ByteArray) = toClient.put(bytes)

    /** Makes the client's next [read] throw [error], as a unplugged cable would. */
    fun failReads(error: IOException = IOException("cable pulled")) = toClient.put(Failure(error))

    /** The next packet the client wrote, or a test failure if none arrives in [timeoutMillis]. */
    fun expect(timeoutMillis: Long = 5_000): AdbMessage {
        val header = AdbHeader.decode(take(AdbWire.HEADER_LENGTH, timeoutMillis))
        return header.toMessage(take(header.dataLength, timeoutMillis))
    }

    fun expect(command: Int, timeoutMillis: Long = 5_000): AdbMessage =
        expect(timeoutMillis).also {
            if (it.command != command) fail<Nothing>("expected ${AdbMessage.commandName(command)} but the client sent $it")
        }

    /** Fails if the client writes anything within [millis]. */
    fun expectSilence(millis: Long = 200) {
        val bytes = fromClient.poll(millis, TimeUnit.MILLISECONDS)
        if (bytes != null) {
            val message = runCatching { AdbHeader.decode(bytes).let { AdbMessage(it.command, it.arg0, it.arg1) } }
            fail<Nothing>("expected silence but the client wrote ${message.getOrNull() ?: "${bytes.size} bytes"}")
        }
    }

    /** Waits until the client has closed the channel. */
    fun awaitClosed(timeoutMillis: Long = 5_000): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (!isClosed && System.nanoTime() < deadline) Thread.sleep(5)
        return isClosed
    }

    private fun take(count: Int, timeoutMillis: Long): ByteArray {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (received.size < count) {
            val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            val next = (if (remaining > 0) fromClient.poll(remaining, TimeUnit.MILLISECONDS) else null)
                ?: fail<Nothing>("timed out waiting for the client to write ($count bytes needed, ${received.size} received)")
            received += next
        }
        return received.copyOfRange(0, count).also { received = received.copyOfRange(count, received.size) }
    }
}

/** What a device sends first: CNXN with [version], [maxPayload] and a banner. */
fun deviceCnxn(maxPayload: Int = 4096, version: Int = 0x01000000, banner: String = "device::features=shell_v2"): AdbMessage =
    AdbMessage(AdbWire.CNXN, version, maxPayload, (banner + "\u0000").toByteArray())

/** An [AdbIdentity] that signs predictably, so a test can recognise what was sent. */
class FakeIdentity : AdbIdentity {
    override fun sign(token: ByteArray): ByteArray = "sig:".toByteArray() + token
    override fun publicKeyPayload(): ByteArray = "pubkey\u0000".toByteArray()
}

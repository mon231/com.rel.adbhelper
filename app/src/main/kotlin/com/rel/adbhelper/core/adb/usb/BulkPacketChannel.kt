package com.rel.adbhelper.core.adb.usb

import java.io.IOException
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.thread
import kotlin.concurrent.write

/** The few primitive operations [BulkPacketChannel] needs from a USB device, so its logic runs
 * on the JVM. The Android implementation is [UsbPacketChannel]. */
interface BulkEndpoints {
    /** Largest packet the OUT endpoint sends in one piece. */
    val outMaxPacketSize: Int

    /** One transfer on the IN endpoint; returns the platform's result: bytes read, or -1 for a
     * timeout and a failure alike. */
    fun transferIn(buffer: ByteArray, offset: Int, length: Int, timeoutMillis: Int): Int

    /** One transfer on the OUT endpoint; returns bytes sent, or a negative number on failure. */
    fun transferOut(buffer: ByteArray, offset: Int, length: Int, timeoutMillis: Int): Int

    /** Releases the interface and closes the device. Only called once no transfer is running. */
    fun release()
}

/**
 * [AdbPacketChannel] over the bulk endpoints of an ADB USB interface.
 *
 * Reads time out after [READ_TIMEOUT_MILLIS] and then return 0, so that no thread stays inside
 * the platform call for long. [close] only marks the channel closed and the release of the
 * interface follows in the background once no transfer is running: freeing the native device
 * while another thread is inside `bulkTransfer` is not safe.
 */
class BulkPacketChannel(
    private val endpoints: BulkEndpoints,
    private val nanoTime: () -> Long = System::nanoTime,
) : AdbPacketChannel {

    /** Held shared by every running transfer; the release takes it exclusively. */
    private val transferLock = ReentrantReadWriteLock()

    @Volatile
    private var closed = false

    override fun write(bytes: ByteArray) = transferLock.read {
        var offset = 0
        while (offset < bytes.size) {
            ensureOpen()
            val sent = endpoints.transferOut(bytes, offset, bytes.size - offset, WRITE_TIMEOUT_MILLIS)
            if (sent <= 0) throw IOException("USB write failed ($sent)")
            offset += sent
        }
        // A transfer that ends exactly on a packet boundary needs an empty packet to be seen as
        // finished by the device.
        if (bytes.isNotEmpty() && bytes.size % endpoints.outMaxPacketSize == 0) {
            ensureOpen()
            if (endpoints.transferOut(ByteArray(0), 0, 0, WRITE_TIMEOUT_MILLIS) < 0) {
                throw IOException("USB write failed (zero-length packet)")
            }
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = transferLock.read {
        ensureOpen()
        if (length <= 0) return 0
        val started = nanoTime()
        val count = endpoints.transferIn(buffer, offset, length, READ_TIMEOUT_MILLIS)
        if (count >= 0) return count
        ensureOpen()
        // The platform reports a timeout and a failure alike as -1. A failure that comes back
        // long before the timeout would have (device unplugged) must not turn into a busy loop.
        val elapsedMillis = (nanoTime() - started) / 1_000_000
        if (elapsedMillis < READ_TIMEOUT_MILLIS / 2) throw IOException("USB read failed (device removed?)")
        0
    }

    override fun close() {
        if (closed) return
        closed = true
        thread(name = "adb-usb-release", isDaemon = true) {
            transferLock.write { endpoints.release() }
        }
    }

    private fun ensureOpen() {
        if (closed) throw IOException("USB channel closed")
    }

    companion object {
        const val READ_TIMEOUT_MILLIS = 1_000
        const val WRITE_TIMEOUT_MILLIS = 5_000
    }
}

package com.rel.adbhelper.core.adb.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.IOException
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.thread
import kotlin.concurrent.write

private const val ADB_CLASS = 0xFF
private const val ADB_SUBCLASS = 0x42
private const val ADB_PROTOCOL = 0x01

/** The interface of [device] that speaks ADB (vendor class 0xFF, subclass 0x42, protocol 1, one
 * bulk IN and one bulk OUT endpoint), or null if it has none. */
fun findAdbInterface(device: UsbDevice): UsbInterface? =
    (0 until device.interfaceCount).map { device.getInterface(it) }.firstOrNull {
        it.interfaceClass == ADB_CLASS && it.interfaceSubclass == ADB_SUBCLASS &&
            it.interfaceProtocol == ADB_PROTOCOL && bulkEndpoints(it) != null
    }

private fun bulkEndpoints(iface: UsbInterface): Pair<UsbEndpoint, UsbEndpoint>? {
    val bulk = (0 until iface.endpointCount).map { iface.getEndpoint(it) }
        .filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
    val bulkIn = bulk.firstOrNull { it.direction == UsbConstants.USB_DIR_IN } ?: return null
    val bulkOut = bulk.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT } ?: return null
    return bulkIn to bulkOut
}

/**
 * [AdbPacketChannel] over the bulk endpoints of a device's ADB interface. Needs the user's USB
 * permission for the device; cannot be unit-tested (it is a thin layer over the platform).
 *
 * Reads time out after [READ_TIMEOUT_MILLIS] and then return 0, so that no thread stays inside
 * the platform call for long. [close] only marks the channel closed and the release of the
 * interface follows in the background once no transfer is running: freeing the native device
 * while another thread is inside `bulkTransfer` is not safe.
 */
class UsbPacketChannel private constructor(
    private val connection: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val bulkIn: UsbEndpoint,
    private val bulkOut: UsbEndpoint,
) : AdbPacketChannel {

    /** Held shared by every running transfer; the release takes it exclusively. */
    private val transferLock = ReentrantReadWriteLock()

    @Volatile
    private var closed = false

    override fun write(bytes: ByteArray) = transferLock.read {
        var offset = 0
        while (offset < bytes.size) {
            ensureOpen()
            val sent = connection.bulkTransfer(bulkOut, bytes, offset, bytes.size - offset, WRITE_TIMEOUT_MILLIS)
            if (sent <= 0) throw IOException("USB write failed ($sent)")
            offset += sent
        }
        // A transfer that ends exactly on a packet boundary needs an empty packet to be seen as
        // finished by the device.
        if (bytes.isNotEmpty() && bytes.size % bulkOut.maxPacketSize == 0) {
            ensureOpen()
            if (connection.bulkTransfer(bulkOut, ByteArray(0), 0, 0, WRITE_TIMEOUT_MILLIS) < 0) {
                throw IOException("USB write failed (zero-length packet)")
            }
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = transferLock.read {
        ensureOpen()
        if (length <= 0) return 0
        val started = System.nanoTime()
        val count = connection.bulkTransfer(bulkIn, buffer, offset, length, READ_TIMEOUT_MILLIS)
        if (count >= 0) return count
        ensureOpen()
        // The platform reports a timeout and a failure alike as -1. A failure that comes back
        // long before the timeout would have (device unplugged) must not turn into a busy loop.
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        if (elapsedMillis < READ_TIMEOUT_MILLIS / 2) throw IOException("USB read failed (device removed?)")
        0
    }

    override fun close() {
        if (closed) return
        closed = true
        thread(name = "adb-usb-release", isDaemon = true) {
            transferLock.write {
                runCatching { connection.releaseInterface(iface) }
                runCatching { connection.close() }
            }
        }
    }

    private fun ensureOpen() {
        if (closed) throw IOException("USB channel closed")
    }

    companion object {
        private const val READ_TIMEOUT_MILLIS = 1_000
        private const val WRITE_TIMEOUT_MILLIS = 5_000

        /** Opens [device] and claims its ADB interface. The app must already hold permission. */
        fun open(usbManager: UsbManager, device: UsbDevice): UsbPacketChannel {
            val iface = findAdbInterface(device) ?: throw IOException("The USB device has no ADB interface")
            val (bulkIn, bulkOut) = bulkEndpoints(iface)!!
            val connection = usbManager.openDevice(device) ?: throw IOException("Could not open the USB device")
            if (!connection.claimInterface(iface, true)) {
                connection.close()
                throw IOException("Could not claim the ADB interface of the USB device")
            }
            return UsbPacketChannel(connection, iface, bulkIn, bulkOut)
        }
    }
}

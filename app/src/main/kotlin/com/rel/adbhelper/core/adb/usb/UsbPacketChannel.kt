package com.rel.adbhelper.core.adb.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.IOException

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
 * Thin Android adapter that lets [BulkPacketChannel] run over a [UsbDeviceConnection]; it has no
 * logic of its own and needs the user's USB permission for the device.
 */
class UsbPacketChannel private constructor(
    private val connection: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val bulkIn: UsbEndpoint,
    private val bulkOut: UsbEndpoint,
) : BulkEndpoints {

    override val outMaxPacketSize: Int get() = bulkOut.maxPacketSize

    override fun transferIn(buffer: ByteArray, offset: Int, length: Int, timeoutMillis: Int): Int =
        connection.bulkTransfer(bulkIn, buffer, offset, length, timeoutMillis)

    override fun transferOut(buffer: ByteArray, offset: Int, length: Int, timeoutMillis: Int): Int =
        connection.bulkTransfer(bulkOut, buffer, offset, length, timeoutMillis)

    override fun release() {
        runCatching { connection.releaseInterface(iface) }
        runCatching { connection.close() }
    }

    companion object {
        /** Opens [device] and claims its ADB interface. The app must already hold permission. */
        fun open(usbManager: UsbManager, device: UsbDevice): AdbPacketChannel {
            val iface = findAdbInterface(device) ?: throw IOException("The USB device has no ADB interface")
            val (bulkIn, bulkOut) = bulkEndpoints(iface)!!
            val connection = usbManager.openDevice(device) ?: throw IOException("Could not open the USB device")
            if (!connection.claimInterface(iface, true)) {
                connection.close()
                throw IOException("Could not claim the ADB interface of the USB device")
            }
            return BulkPacketChannel(UsbPacketChannel(connection, iface, bulkIn, bulkOut))
        }
    }
}

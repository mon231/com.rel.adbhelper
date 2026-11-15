package com.rel.adbhelper.core.adb.usb

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The peer sent something that is not a valid ADB packet (or not valid at this point). */
class AdbProtocolException(message: String) : IOException(message)

/** Constants of the ADB wire protocol (AOSP `packages/modules/adb/protocol.txt`). */
object AdbWire {
    const val HEADER_LENGTH = 24

    // Command words are four ASCII characters read as a little-endian 32-bit integer.
    const val CNXN = 0x4e584e43
    const val AUTH = 0x48545541
    const val OPEN = 0x4e45504f
    const val OKAY = 0x59414b4f
    const val CLSE = 0x45534c43
    const val WRTE = 0x45545257

    const val AUTH_TOKEN = 1
    const val AUTH_SIGNATURE = 2
    const val AUTH_RSAPUBLICKEY = 3

    /** The protocol version this client speaks. Deliberately the original version rather than
     * 0x01000001: from 0x01000001 on, both sides stop filling in the payload checksum, and
     * keeping checksums on means a framing error is detected instead of silently mis-parsed. */
    const val VERSION = 0x01000000

    /** Largest payload this client accepts in one packet (what we advertise in our CNXN). */
    const val MAX_PAYLOAD = 256 * 1024

    /** `<systemtype>:<serialno>:<banner>` with only the system type filled in, NUL-terminated. */
    val HOST_BANNER: ByteArray = "host::\u0000".toByteArray(Charsets.US_ASCII)

    /** Size of the random token a device sends in AUTH TOKEN. */
    const val AUTH_TOKEN_LENGTH = 20
}

/** The payload checksum the protocol calls `data_crc32`: not a CRC at all, just the sum of the
 * payload bytes taken as unsigned values, modulo 2^32. */
fun adbChecksum(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
    var sum = 0
    for (i in offset until offset + length) sum += data[i].toInt() and 0xFF
    return sum
}

/** One ADB packet: a command, its two arguments and an optional payload. */
class AdbMessage(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val payload: ByteArray = EMPTY,
) {
    /** The 24-byte little-endian header describing this message. Sent as its own transfer on
     * USB, separately from [payload]. */
    fun encodeHeader(): ByteArray =
        ByteBuffer.allocate(AdbWire.HEADER_LENGTH).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command)
            .putInt(arg0)
            .putInt(arg1)
            .putInt(payload.size)
            .putInt(adbChecksum(payload))
            .putInt(command.inv())
            .array()

    /** Header immediately followed by the payload. */
    fun encode(): ByteArray = encodeHeader() + payload

    override fun toString(): String =
        "AdbMessage(${commandName(command)}, arg0=$arg0, arg1=$arg1, payload=${payload.size} bytes)"

    companion object {
        private val EMPTY = ByteArray(0)

        fun connect(): AdbMessage =
            AdbMessage(AdbWire.CNXN, AdbWire.VERSION, AdbWire.MAX_PAYLOAD, AdbWire.HOST_BANNER)

        fun authSignature(signature: ByteArray): AdbMessage =
            AdbMessage(AdbWire.AUTH, AdbWire.AUTH_SIGNATURE, 0, signature)

        fun authPublicKey(publicKey: ByteArray): AdbMessage =
            AdbMessage(AdbWire.AUTH, AdbWire.AUTH_RSAPUBLICKEY, 0, publicKey)

        /** Asks the peer to connect a new stream, known to us as [localId], to [service]. */
        fun open(localId: Int, service: String): AdbMessage {
            require(localId != 0) { "local stream id must not be 0" }
            return AdbMessage(AdbWire.OPEN, localId, 0, (service + "\u0000").toByteArray(Charsets.UTF_8))
        }

        fun okay(localId: Int, remoteId: Int): AdbMessage = AdbMessage(AdbWire.OKAY, localId, remoteId)

        fun write(localId: Int, remoteId: Int, data: ByteArray): AdbMessage =
            AdbMessage(AdbWire.WRTE, localId, remoteId, data)

        fun close(localId: Int, remoteId: Int): AdbMessage = AdbMessage(AdbWire.CLSE, localId, remoteId)

        fun commandName(command: Int): String {
            val chars = CharArray(4) { i -> ((command ushr (8 * i)) and 0xFF).toChar() }
            return if (chars.all { it in 'A'..'Z' }) String(chars) else "0x%08x".format(command)
        }
    }
}

/** A decoded and validated packet header, before its payload has been read. */
class AdbHeader private constructor(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val dataLength: Int,
    val dataChecksum: Int,
) {
    /** Combines this header with the [payload] that followed it, verifying length and checksum. */
    fun toMessage(payload: ByteArray): AdbMessage {
        if (payload.size != dataLength) {
            throw AdbProtocolException("ADB payload is ${payload.size} bytes, header announced $dataLength")
        }
        val actual = adbChecksum(payload)
        if (actual != dataChecksum) {
            throw AdbProtocolException(
                "ADB payload checksum mismatch on ${AdbMessage.commandName(command)}: " +
                    "header says 0x%08x, payload sums to 0x%08x".format(dataChecksum, actual),
            )
        }
        return AdbMessage(command, arg0, arg1, payload)
    }

    companion object {
        /**
         * Decodes the first [AdbWire.HEADER_LENGTH] bytes of [bytes]. Rejects a header whose magic
         * is not the bitwise complement of its command (the stream is out of sync or this is not
         * ADB at all) or whose payload is larger than [maxPayload] (the limit we advertised).
         */
        fun decode(bytes: ByteArray, maxPayload: Int = AdbWire.MAX_PAYLOAD): AdbHeader {
            if (bytes.size < AdbWire.HEADER_LENGTH) {
                throw AdbProtocolException("ADB header truncated: ${bytes.size} of ${AdbWire.HEADER_LENGTH} bytes")
            }
            val buffer = ByteBuffer.wrap(bytes, 0, AdbWire.HEADER_LENGTH).order(ByteOrder.LITTLE_ENDIAN)
            val command = buffer.int
            val arg0 = buffer.int
            val arg1 = buffer.int
            val dataLength = buffer.int
            val dataChecksum = buffer.int
            val magic = buffer.int
            if (magic != command.inv()) {
                throw AdbProtocolException("ADB header has a bad magic (command 0x%08x, magic 0x%08x)".format(command, magic))
            }
            // dataLength is unsigned on the wire: a negative Int here is a length above 2^31.
            if (dataLength < 0 || dataLength > maxPayload) {
                throw AdbProtocolException(
                    "ADB payload of ${dataLength.toUInt()} bytes exceeds the negotiated maximum of $maxPayload",
                )
            }
            return AdbHeader(command, arg0, arg1, dataLength, dataChecksum)
        }
    }
}

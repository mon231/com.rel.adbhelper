package com.rel.adbhelper.core.adb.usb

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal fun hex(text: String): ByteArray =
    text.filterNot { it.isWhitespace() }.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

class AdbMessageTest {

    @Test
    fun `command words are the ASCII names read little-endian`() {
        fun word(name: String) = name.toByteArray(Charsets.US_ASCII)
            .foldIndexed(0) { i, acc, b -> acc or ((b.toInt() and 0xFF) shl (8 * i)) }
        assertEquals(word("CNXN"), AdbWire.CNXN)
        assertEquals(word("AUTH"), AdbWire.AUTH)
        assertEquals(word("OPEN"), AdbWire.OPEN)
        assertEquals(word("OKAY"), AdbWire.OKAY)
        assertEquals(word("CLSE"), AdbWire.CLSE)
        assertEquals(word("WRTE"), AdbWire.WRTE)
    }

    @Test
    fun `our CNXN matches the known byte vector`() {
        val expected = hex(
            "434e584e" + // CNXN
                "00000001" + // version 0x01000000
                "00000400" + // maxdata 256 KiB
                "07000000" + // payload length
                "32020000" + // sum of "host::\0"
                "bcb1a7b1" + // CNXN xor 0xffffffff
                "686f73743a3a00", // host::\0
        )
        assertArrayEquals(expected, AdbMessage.connect().encode())
    }

    @Test
    fun `OPEN carries the local id and a NUL-terminated service`() {
        val expected = hex(
            "4f50454e" + "05000000" + "00000000" + "0a000000" + "60030000" + "b0afbab1" +
                "7368656c6c3a6c732f00", // shell:ls/\0
        )
        assertArrayEquals(expected, AdbMessage.open(5, "shell:ls/").encode())
    }

    @Test
    fun `OKAY and CLSE have no payload and a zero checksum`() {
        assertArrayEquals(
            hex("4f4b4159" + "01000000" + "2a000000" + "00000000" + "00000000" + "b0b4bea6"),
            AdbMessage.okay(1, 42).encode(),
        )
        assertArrayEquals(
            hex("434c5345" + "01000000" + "2a000000" + "00000000" + "00000000" + "bcb3acba"),
            AdbMessage.close(1, 42).encode(),
        )
    }

    @Test
    fun `WRTE checksum sums payload bytes as unsigned values`() {
        val message = AdbMessage.write(2, 3, hex("ffff80"))
        assertArrayEquals(
            hex("57525445" + "02000000" + "03000000" + "03000000" + "7e020000" + "a8adabba" + "ffff80"),
            message.encode(),
        )
        assertEquals(0x27e, adbChecksum(hex("ffff80")))
    }

    @Test
    fun `AUTH messages carry their type in arg0`() {
        val signature = AdbMessage.authSignature(byteArrayOf(1, 2))
        assertEquals(AdbWire.AUTH, signature.command)
        assertEquals(2, signature.arg0)
        val key = AdbMessage.authPublicKey(byteArrayOf(9))
        assertEquals(3, key.arg0)
        assertArrayEquals(
            hex("41555448" + "03000000" + "00000000" + "01000000" + "09000000" + "beaaabb7" + "09"),
            key.encode(),
        )
    }

    @Test
    fun `a header round-trips through decode`() {
        val original = AdbMessage(AdbWire.WRTE, -1, Int.MIN_VALUE, byteArrayOf(-1, 0, 127))
        val header = AdbHeader.decode(original.encodeHeader())
        val decoded = header.toMessage(original.payload)
        assertEquals(AdbWire.WRTE, decoded.command)
        assertEquals(-1, decoded.arg0)
        assertEquals(Int.MIN_VALUE, decoded.arg1)
        assertArrayEquals(original.payload, decoded.payload)
    }

    @Test
    fun `decoding a device AUTH TOKEN header from raw bytes`() {
        val header = AdbHeader.decode(
            hex("41555448" + "01000000" + "00000000" + "14000000" + "f6090000" + "beaaabb7"),
        )
        assertEquals(AdbWire.AUTH, header.command)
        assertEquals(AdbWire.AUTH_TOKEN, header.arg0)
        assertEquals(20, header.dataLength)
        assertEquals(0x9f6, header.dataChecksum)
    }

    @Test
    fun `bad magic is rejected`() {
        val bytes = AdbMessage.okay(1, 2).encodeHeader()
        bytes[20] = (bytes[20].toInt() xor 1).toByte()
        val error = assertThrows(AdbProtocolException::class.java) { AdbHeader.decode(bytes) }
        assertTrue(error.message!!.contains("magic"))
    }

    @Test
    fun `truncated header is rejected`() {
        assertThrows(AdbProtocolException::class.java) { AdbHeader.decode(ByteArray(23)) }
    }

    @Test
    fun `payload larger than the negotiated maximum is rejected`() {
        val atLimit = AdbMessage(AdbWire.WRTE, 1, 2, ByteArray(16)).encodeHeader()
        assertEquals(16, AdbHeader.decode(atLimit, maxPayload = 16).dataLength)
        assertThrows(AdbProtocolException::class.java) { AdbHeader.decode(atLimit, maxPayload = 15) }
    }

    @Test
    fun `a length with the top bit set is treated as unsigned and rejected`() {
        // WRTE, length 0x80000000
        val bytes = hex("57525445" + "01000000" + "02000000" + "00000080" + "00000000" + "a8adabba")
        assertThrows(AdbProtocolException::class.java) { AdbHeader.decode(bytes) }
    }

    @Test
    fun `payload checksum mismatch is rejected`() {
        val header = AdbHeader.decode(AdbMessage.write(1, 2, byteArrayOf(1, 2, 3)).encodeHeader())
        assertThrows(AdbProtocolException::class.java) { header.toMessage(byteArrayOf(1, 2, 4)) }
    }

    @Test
    fun `payload length mismatch is rejected`() {
        val header = AdbHeader.decode(AdbMessage.write(1, 2, byteArrayOf(1, 2, 3)).encodeHeader())
        assertThrows(AdbProtocolException::class.java) { header.toMessage(byteArrayOf(6)) }
    }

    @Test
    fun `command names render readably`() {
        assertEquals("WRTE", AdbMessage.commandName(AdbWire.WRTE))
        assertEquals("0x00000001", AdbMessage.commandName(1))
    }
}

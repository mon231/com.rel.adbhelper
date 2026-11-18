package com.rel.adbhelper.core.scrcpy

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

/** Delivers [data] in chunks of [chunkSize] bytes, then end of stream. */
private fun readerOf(data: ByteArray, chunkSize: Int): ByteStreamReader {
    var offset = 0
    return ByteStreamReader {
        if (offset >= data.size) null
        else data.copyOfRange(offset, minOf(offset + chunkSize, data.size)).also { offset += it.size }
    }
}

class ScrcpyProtocolTest {

    @Test
    fun `socket name is eight lowercase hex digits`() {
        assertEquals("scrcpy_0000abcd", ScrcpyProtocol.socketName(0xabcd))
        assertEquals("localabstract:scrcpy_7fffffff", ScrcpyProtocol.socketService(0x7fffffff))
    }

    @Test
    fun `server command carries the pinned version and video-only options`() {
        val command = ScrcpyProtocol.serverCommand(scid = 0x1234, maxSize = 1024, videoBitRate = 2_000_000)
        assertTrue(
            command.startsWith(
                "CLASSPATH=/data/local/tmp/scrcpy-server.jar app_process / com.genymobile.scrcpy.Server 5.0.1 ",
            ),
            command,
        )
        val args = command.split(' ').toSet()
        for (expected in listOf(
            "scid=00001234", "tunnel_forward=true", "video=true", "audio=false", "control=true", "cleanup=true",
            "video_codec=h264", "max_size=1024", "video_bit_rate=2000000",
        )) assertTrue(expected in args, "missing $expected in $command")
    }

    @Test
    fun `reader reassembles reads from one byte chunks`() = runBlocking {
        val reader = readerOf(bytes(1, 2, 3, 4, 5, 6, 7), chunkSize = 1)
        assertArrayEquals(bytes(1, 2, 3), reader.readExactly(3))
        assertArrayEquals(bytes(), reader.readExactly(0))
        assertArrayEquals(bytes(4, 5, 6, 7), reader.readExactly(4))
        assertThrows<IOException> { reader.readExactly(1) }
        Unit
    }

    @Test
    fun `reader reports a stream that ends mid read`() = runBlocking {
        val reader = readerOf(bytes(1, 2), chunkSize = 5)
        assertThrows<IOException> { reader.readExactly(3) }
        Unit
    }

    private fun preamble(name: String, codec: Int = ScrcpyProtocol.CODEC_H264): ByteArray {
        val field = ByteArray(64)
        name.toByteArray().copyInto(field)
        return bytes(0) + field + bytes(codec ushr 24, codec ushr 16, codec ushr 8, codec)
    }

    @Test
    fun `preamble is dummy byte then device name then codec id, in any chunking`() = runBlocking {
        for (chunk in listOf(1, 3, 64, 1000)) {
            val reader = readerOf(preamble("Pixel 7"), chunk)
            ScrcpyProtocol.readDummyByte(reader)
            val info = ScrcpyProtocol.readStreamInfo(reader)
            assertEquals("Pixel 7", info.deviceName)
            assertEquals(0x68323634, info.codecId)
        }
    }

    @Test
    fun `a name filling all 64 bytes has no terminator`() = runBlocking {
        val name = "a".repeat(64)
        val reader = readerOf(preamble(name), 7)
        ScrcpyProtocol.readDummyByte(reader)
        assertEquals(name, ScrcpyProtocol.readStreamInfo(reader).deviceName)
    }

    @Test
    fun `disabled stream codec ids and other codecs are rejected`() = runBlocking {
        for (codec in listOf(0, 1, 0x68323635)) {
            val reader = readerOf(preamble("x", codec), 10)
            ScrcpyProtocol.readDummyByte(reader)
            assertThrows<IOException> { ScrcpyProtocol.readStreamInfo(reader) }
        }
        Unit
    }

    @Test
    fun `session packet carries the video size`() = runBlocking {
        // develop.md: byte 0 MSB set, byte 3 bit 0 = client resized, then width and height as u32.
        val packet = bytes(0x80, 0, 0, 0, 0, 0, 0x04, 0x38, 0, 0, 0x07, 0x80)
        val parsed = ScrcpyProtocol.readVideoPacket(readerOf(packet, 1))
        assertEquals(ScrcpyProtocol.VideoPacket.Session(1080, 1920, clientResized = false), parsed)
        val resized = ScrcpyProtocol.readVideoPacket(readerOf(bytes(0x80, 0, 0, 1) + packet.copyOfRange(4, 12), 5))
        assertEquals(ScrcpyProtocol.VideoPacket.Session(1080, 1920, clientResized = true), resized)
    }

    @Test
    fun `config packet has only the config flag and a payload`() = runBlocking {
        val packet = bytes(0x40, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 3, 0xAA, 0xBB, 0xCC)
        val parsed = ScrcpyProtocol.readVideoPacket(readerOf(packet, 1))
        assertTrue(parsed is ScrcpyProtocol.VideoPacket.Config)
        assertArrayEquals(bytes(0xAA, 0xBB, 0xCC), (parsed as ScrcpyProtocol.VideoPacket.Config).data)
    }

    @Test
    fun `media packet decodes pts and key frame flag`() = runBlocking {
        // key frame flag (0x20) set, pts = 0x0102030405 microseconds, 2 byte payload.
        val key = bytes(0x20, 0, 0, 1, 2, 3, 4, 5, 0, 0, 0, 2, 9, 8)
        val parsed = ScrcpyProtocol.readVideoPacket(readerOf(key, 4)) as ScrcpyProtocol.VideoPacket.Media
        assertTrue(parsed.keyFrame)
        assertEquals(0x0102030405L, parsed.ptsMicros)
        assertArrayEquals(bytes(9, 8), parsed.data)

        val delta = bytes(0, 0, 0, 0, 0, 0, 0, 7, 0, 0, 0, 1, 5)
        val p = ScrcpyProtocol.readVideoPacket(readerOf(delta, 2)) as ScrcpyProtocol.VideoPacket.Media
        assertFalse(p.keyFrame)
        assertEquals(7L, p.ptsMicros)
    }

    @Test
    fun `consecutive packets split across arbitrary chunks stay aligned`() = runBlocking {
        val stream = bytes(0x80, 0, 0, 0, 0, 0, 0, 10, 0, 0, 0, 20) +
            bytes(0x40, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0x67) +
            bytes(0x20, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 2, 1, 2) +
            bytes(0, 0, 0, 0, 0, 0, 0, 2, 0, 0, 0, 0)
        for (chunk in listOf(1, 2, 7, 12, 100)) {
            val reader = readerOf(stream, chunk)
            assertEquals(ScrcpyProtocol.VideoPacket.Session(10, 20, false), ScrcpyProtocol.readVideoPacket(reader))
            assertTrue(ScrcpyProtocol.readVideoPacket(reader) is ScrcpyProtocol.VideoPacket.Config)
            assertTrue((ScrcpyProtocol.readVideoPacket(reader) as ScrcpyProtocol.VideoPacket.Media).keyFrame)
            assertEquals(0, (ScrcpyProtocol.readVideoPacket(reader) as ScrcpyProtocol.VideoPacket.Media).data.size)
            assertThrows<IOException> { ScrcpyProtocol.readVideoPacket(reader) }
        }
    }

    @Test
    fun `an absurd packet size is rejected instead of allocated`() = runBlocking {
        val packet = bytes(0, 0, 0, 0, 0, 0, 0, 0, 0xFF, 0xFF, 0xFF, 0xFF)
        assertThrows<IOException> { ScrcpyProtocol.readVideoPacket(readerOf(packet, 12)) }
        Unit
    }

    @Test
    fun `a zero sized session is rejected`() = runBlocking {
        assertThrows<IOException> {
            ScrcpyProtocol.readVideoPacket(readerOf(bytes(0x80, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), 12))
        }
        Unit
    }
}

class ControlMessagesTest {

    @Test
    fun `touch down matches the client's serialization test vector`() {
        // test_serialize_inject_touch_event in app/tests/test_control_msg_serialize.c
        val expected = bytes(
            2, 0,
            0x12, 0x34, 0x56, 0x78, 0x87, 0x65, 0x43, 0x21,
            0, 0, 0, 0x64, 0, 0, 0, 0xc8,
            0x04, 0x38, 0x07, 0x80,
            0xff, 0xff,
            0, 0, 0, 1,
            0, 0, 0, 1,
        )
        val actual = ControlMessages.injectTouch(
            ControlMessages.ACTION_DOWN, 0x1234567887654321L, 100, 200, 1080, 1920, 1.0f,
            actionButton = 1, buttons = 1,
        )
        assertEquals(32, actual.size)
        assertArrayEquals(expected, actual)
    }

    @Test
    fun `finger touch has no buttons and a lifted finger has zero pressure`() {
        val up = ControlMessages.injectTouch(ControlMessages.ACTION_UP, 3, 5, 6, 720, 1280, 0f)
        assertEquals(32, up.size)
        assertEquals(1, up[1].toInt())
        assertEquals(3, up[9].toInt())
        assertArrayEquals(bytes(0, 0), up.copyOfRange(22, 24))
        assertArrayEquals(bytes(0, 0, 0, 0, 0, 0, 0, 0), up.copyOfRange(24, 32))
    }

    @Test
    fun `keycode matches the client's serialization test vector`() {
        // test_serialize_inject_keycode: UP, ENTER (0x42), repeat 5, shift meta 0x41
        assertArrayEquals(
            bytes(0, 1, 0, 0, 0, 0x42, 0, 0, 0, 5, 0, 0, 0, 0x41),
            ControlMessages.injectKeycode(ControlMessages.ACTION_UP, 0x42, 5, 0x41),
        )
    }

    @Test
    fun `navigation keycodes`() {
        assertEquals(4, ControlMessages.KEYCODE_BACK)
        assertEquals(3, ControlMessages.KEYCODE_HOME)
        assertEquals(187, ControlMessages.KEYCODE_APP_SWITCH)
        assertArrayEquals(
            bytes(0, 0, 0, 0, 0, 187, 0, 0, 0, 0, 0, 0, 0, 0),
            ControlMessages.injectKeycode(ControlMessages.ACTION_DOWN, ControlMessages.KEYCODE_APP_SWITCH),
        )
    }

    @Test
    fun `back or screen on and reset video match the test vectors`() {
        assertArrayEquals(bytes(4, 1), ControlMessages.backOrScreenOn(ControlMessages.ACTION_UP))
        assertArrayEquals(bytes(17), ControlMessages.resetVideo())
    }

    @Test
    fun `pressure encoding`() {
        assertEquals(0xffff, ControlMessages.pressureToU16(1f))
        assertEquals(0, ControlMessages.pressureToU16(0f))
        assertEquals(0x8000, ControlMessages.pressureToU16(0.5f))
        assertEquals(0xffff, ControlMessages.pressureToU16(7f))
    }
}

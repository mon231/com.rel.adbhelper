package com.rel.adbhelper.core.scrcpy

import com.rel.adbhelper.core.adb.AdbClient
import com.rel.adbhelper.core.adb.AdbStream
import com.rel.adbhelper.core.adb.AdbTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

internal object Eof

/** A stream fed by the test. Reads block for real; only [close] (or end of data) ends them, like the library's. */
internal class QueueStream(vararg initial: ByteArray) : AdbStream {
    private val queue = LinkedBlockingQueue<Any>()
    val closeCount = AtomicInteger()
    val written = mutableListOf<ByteArray>()

    init { initial.forEach { queue.put(it) } }

    fun feed(bytes: ByteArray) = queue.put(bytes)
    fun endOfStream() = queue.put(Eof)

    override suspend fun write(data: ByteArray) { synchronized(written) { written.add(data) } }
    override suspend fun readChunk(): ByteArray? = when (val next = withContext(Dispatchers.IO) { queue.take() }) {
        is Throwable -> throw next
        Eof -> null
        else -> next as ByteArray
    }
    override fun close() {
        closeCount.incrementAndGet()
        queue.put(IOException("Stream closed."))
    }
    fun writtenBytes(): List<ByteArray> = synchronized(written) { written.toList() }
}

/** A target scripted per service string. */
internal class ScriptedTransport(
    private val scid: Int,
    var remoteJarSize: Long,
    val server: QueueStream,
    val video: QueueStream,
    val control: QueueStream,
    /** How many socket opens are refused before the server "starts listening". */
    var refuseSocketOpens: Int = 0,
    /** The first server launch finds the jar gone (a previous server's cleanup deleted it) and exits. */
    var jarDeletedBeforeFirstLaunch: Boolean = false,
) : AdbTransport {
    val opened = mutableListOf<String>()
    val pushed = QueueStream()
    private var socketOpens = 0
    private var launches = 0

    override suspend fun open(service: String): AdbStream {
        opened.add(service)
        return when {
            service.startsWith("shell:stat -c %s ") ->
                QueueStream("$remoteJarSize\n__ADBHELPER_EXIT__:0\n".toByteArray()).also { it.endOfStream() }
            service.startsWith("exec:cat > ") -> object : AdbStream by pushed {
                override suspend fun write(data: ByteArray) {
                    remoteJarSize += data.size
                    pushed.write(data)
                }
            }
            service.startsWith("shell:CLASSPATH=") ->
                if (jarDeletedBeforeFirstLaunch && launches++ == 0) {
                    remoteJarSize = 0
                    QueueStream("Error: Could not find or load main class\n".toByteArray()).also { it.endOfStream() }
                } else server
            service == ScrcpyProtocol.socketService(scid) -> {
                if (socketOpens++ < refuseSocketOpens || (jarDeletedBeforeFirstLaunch && remoteJarSize == 0L)) throw IOException("closed")
                val which = ++acceptedSockets
                if (which == 1) video else {
                    // The server sends the device name and codec id only once control is accepted.
                    video.feed(DEVICE_NAME_AND_CODEC)
                    control
                }
            }
            else -> throw IOException("unexpected service $service")
        }
    }

    private var acceptedSockets = 0

    companion object {
        val DEVICE_NAME_AND_CODEC: ByteArray = ByteArray(64).also { "Test phone".toByteArray().copyInto(it) } +
            byteArrayOf(0x68, 0x32, 0x36, 0x34)
    }
}

class ScrcpySessionTest {
    private val scid = 0x1234
    private val jar = ByteArray(1000) { it.toByte() }
    private val sessions = mutableListOf<ScrcpySession>()

    private fun newSession(transport: ScriptedTransport, released: AtomicInteger = AtomicInteger()): ScrcpySession =
        ScrcpySession(
            openLink = { ScrcpyLink(AdbClient(transport)) { released.incrementAndGet() } },
            loadServerJar = { jar },
            scid = scid,
            connectAttempts = 6,
            retryDelayMillis = 30,
            preambleTimeoutMillis = 5_000,
        ).also { sessions.add(it) }

    private fun transport(remoteJarSize: Long = 0, refuse: Int = 0) = ScriptedTransport(
        scid, remoteJarSize, QueueStream(), QueueStream(byteArrayOf(0)), QueueStream(), refuse,
    )

    private fun <T> run(block: suspend () -> T): T = runBlocking { withTimeout(20_000) { block() } }

    @Test
    fun `happy path pushes the jar, starts the server, opens video then control and streams packets`() = run {
        val released = AtomicInteger()
        val t = transport(remoteJarSize = 0)
        val s = newSession(t, released)

        assertEquals("Test phone", s.start())

        // Push happened, then the server shell, video socket and control socket, in that order.
        assertEquals(jar.size.toLong(), t.remoteJarSize)
        val services = t.opened.filter { it.startsWith("shell:CLASSPATH=") || it.startsWith("localabstract:") }
        assertEquals(3, services.size)
        assertTrue(services[0].contains("Server 5.0.1 scid=00001234"), services[0])
        assertEquals(ScrcpyProtocol.socketService(scid), services[1])
        assertEquals(ScrcpyProtocol.socketService(scid), services[2])

        // Video: session, config, key frame, delivered in awkward chunks.
        val session = byteArrayOf(0x80.toByte(), 0, 0, 0, 0, 0, 0x02, 0xD0.toByte(), 0, 0, 0x05, 0)
        val config = byteArrayOf(0x40, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2, 0x67, 0x68)
        val frame = byteArrayOf(0x20, 0, 0, 0, 0, 0, 0, 9, 0, 0, 0, 1, 0x65)
        (session + config + frame).toList().chunked(5).forEach { t.video.feed(it.toByteArray()) }
        assertEquals(ScrcpyProtocol.VideoPacket.Session(720, 1280, false), s.readPacket())
        assertArrayEquals(byteArrayOf(0x67, 0x68), (s.readPacket() as ScrcpyProtocol.VideoPacket.Config).data)
        val media = s.readPacket() as ScrcpyProtocol.VideoPacket.Media
        assertTrue(media.keyFrame)
        assertEquals(9L, media.ptsMicros)

        // Control messages reach the control socket in order.
        assertTrue(s.sendControl(ControlMessages.backOrScreenOn(0)))
        assertTrue(s.sendControl(ControlMessages.backOrScreenOn(1)))
        awaitUntil { t.control.writtenBytes().size == 2 }
        assertArrayEquals(byteArrayOf(4, 0), t.control.writtenBytes()[0])
        assertArrayEquals(byteArrayOf(4, 1), t.control.writtenBytes()[1])

        s.close().join()
        assertEquals(1, t.server.closeCount.get())
        assertEquals(1, t.video.closeCount.get())
        assertEquals(1, t.control.closeCount.get())
        assertEquals(1, released.get())
        assertFalse(s.sendControl(byteArrayOf(1)))
        assertThrows<IOException> { s.readPacket() }
        Unit
    }

    @Test
    fun `an up to date jar is not pushed again`() = run {
        val t = transport(remoteJarSize = jar.size.toLong())
        val s = newSession(t)
        s.start()
        assertTrue(t.opened.none { it.startsWith("exec:cat") }, t.opened.toString())
        s.close().join()
    }

    @Test
    fun `a server that finds its jar deleted by the previous server is retried after pushing again`() = run {
        val t = transport(remoteJarSize = jar.size.toLong())
        t.jarDeletedBeforeFirstLaunch = true
        val s = newSession(t)
        assertEquals("Test phone", s.start())
        assertEquals(jar.size.toLong(), t.remoteJarSize)
        assertEquals(2, t.opened.count { it.startsWith("shell:CLASSPATH=") })
        s.close().join()
    }

    @Test
    fun `socket opens refused while the server starts are retried`() = run {
        val t = transport(remoteJarSize = jar.size.toLong(), refuse = 3)
        val s = newSession(t)
        assertEquals("Test phone", s.start())
        s.close().join()
    }

    @Test
    fun `a server that exits with an error before the socket opens surfaces its message`() = run {
        val t = transport(remoteJarSize = jar.size.toLong(), refuse = 100)
        t.server.feed("Aborted: The server version (5.0.1) does not match the client (9)\n".toByteArray())
        t.server.endOfStream()
        val released = AtomicInteger()
        val s = newSession(t, released)

        val e = assertThrows<IOException> { s.start() }
        assertTrue(e.message!!.contains("does not match the client"), e.message)
        // The failed start cleaned up after itself.
        awaitUntil { released.get() == 1 && t.server.closeCount.get() == 1 }
        Unit
    }

    @Test
    fun `refused socket with no server output still fails with a message`() = run {
        val t = transport(remoteJarSize = jar.size.toLong(), refuse = 100)
        val s = newSession(t)
        val e = assertThrows<IOException> { s.start() }
        assertTrue(e.message!!.isNotBlank())
        s.close().join()
    }

    @Test
    fun `close is idempotent and closes each stream once`() = run {
        val t = transport(remoteJarSize = jar.size.toLong())
        val s = newSession(t)
        s.start()
        val first = s.close()
        val second = s.close()
        first.join()
        assertTrue(first === second)
        assertEquals(1, t.server.closeCount.get())
        assertEquals(1, t.video.closeCount.get())
        assertEquals(1, t.control.closeCount.get())
    }

    private suspend fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) kotlinx.coroutines.delay(5)
        assertTrue(condition(), "timed out")
    }
}

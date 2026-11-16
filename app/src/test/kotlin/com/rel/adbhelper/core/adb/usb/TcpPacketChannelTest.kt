package com.rel.adbhelper.core.adb.usb

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Timeout(30)
class TcpPacketChannelTest {

    private val server = ServerSocket(0)
    private val pool = Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }
    private val accepted = pool.submit<Socket> { server.accept() }

    @AfterEach
    fun tearDown() {
        runCatching { accepted.get(1, TimeUnit.SECONDS).close() }
        server.close()
        pool.shutdownNow()
    }

    private fun channel() = TcpPacketChannel("127.0.0.1", server.localPort)

    private fun peer(): Socket = accepted.get(5, TimeUnit.SECONDS)

    @Test
    fun `bytes written arrive and bytes sent are read`() {
        val channel = channel()
        channel.write(byteArrayOf(1, 2, 3))
        val received = ByteArray(3)
        var got = 0
        while (got < 3) got += peer().getInputStream().read(received, got, 3 - got)
        assertArrayEquals(byteArrayOf(1, 2, 3), received)

        peer().getOutputStream().apply { write(byteArrayOf(9, 8)); flush() }
        val buffer = ByteArray(8)
        var count = 0
        while (count == 0) count = channel.read(buffer, 1, 7)
        assertEquals(2, count)
        assertEquals(9, buffer[1].toInt())
        assertEquals(8, buffer[2].toInt())
        channel.close()
    }

    @Test
    fun `a read with nothing to read returns zero after the timeout`() {
        val channel = channel()
        val started = System.nanoTime()
        assertEquals(0, channel.read(ByteArray(4), 0, 4))
        assertTrue((System.nanoTime() - started) / 1_000_000 >= 800)
        channel.close()
    }

    @Test
    fun `close from another thread ends a blocked read`() {
        val channel = channel()
        val reader = pool.submit<Throwable?> {
            try {
                while (true) channel.read(ByteArray(4), 0, 4)
                @Suppress("UNREACHABLE_CODE") null
            } catch (e: IOException) {
                e
            }
        }
        Thread.sleep(200)
        channel.close()
        assertTrue(reader.get(3, TimeUnit.SECONDS) is IOException)
    }

    @Test
    fun `reads and writes after close throw`() {
        val channel = channel()
        channel.close()
        channel.close()
        assertThrows(IOException::class.java) { channel.read(ByteArray(1), 0, 1) }
        assertThrows(IOException::class.java) { channel.write(ByteArray(1)) }
    }

    @Test
    fun `the peer closing the connection fails the read`() {
        val channel = channel()
        peer().close()
        assertThrows(IOException::class.java) { while (true) channel.read(ByteArray(1), 0, 1) }
        channel.close()
    }

    @Test
    fun `connecting to a closed port fails`() {
        val port = server.localPort
        server.close()
        assertThrows(IOException::class.java) { TcpPacketChannel("127.0.0.1", port) }
    }
}

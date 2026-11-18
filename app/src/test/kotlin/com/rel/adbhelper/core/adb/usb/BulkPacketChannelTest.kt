package com.rel.adbhelper.core.adb.usb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@Timeout(30)
class BulkPacketChannelTest {

    /** Scriptable endpoints: each call takes the next scripted result (or the default). */
    private class FakeEndpoints(override val outMaxPacketSize: Int = 512) : BulkEndpoints {
        val outLengths = mutableListOf<Int>()
        val outResults = ArrayDeque<Int>()
        var inResult: (ByteArray, Int, Int) -> Int = { _, _, _ -> -1 }
        val released = CountDownLatch(1)

        override fun transferIn(buffer: ByteArray, offset: Int, length: Int, timeoutMillis: Int): Int =
            inResult(buffer, offset, length)

        override fun transferOut(buffer: ByteArray, offset: Int, length: Int, timeoutMillis: Int): Int {
            synchronized(outLengths) { outLengths += length }
            return outResults.removeFirstOrNull() ?: length
        }

        override fun release() = released.countDown()
    }

    private val pool = Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }

    @Test
    fun `a short write is continued from where it stopped`() {
        val endpoints = FakeEndpoints().apply { outResults += listOf(100, 50) }
        BulkPacketChannel(endpoints).write(ByteArray(300))
        assertEquals(listOf(300, 200, 150), endpoints.outLengths)
    }

    @Test
    fun `a failed write throws`() {
        for (bad in listOf(-1, 0)) {
            val endpoints = FakeEndpoints().apply { outResults += bad }
            assertThrows(IOException::class.java) { BulkPacketChannel(endpoints).write(ByteArray(10)) }
        }
    }

    @Test
    fun `an exact multiple of the packet size is followed by one empty packet`() {
        val endpoints = FakeEndpoints(outMaxPacketSize = 512)
        BulkPacketChannel(endpoints).write(ByteArray(1024))
        assertEquals(listOf(1024, 0), endpoints.outLengths)
    }

    @Test
    fun `no empty packet for a non-multiple or an empty write`() {
        val endpoints = FakeEndpoints(outMaxPacketSize = 512)
        val channel = BulkPacketChannel(endpoints)
        channel.write(ByteArray(513))
        channel.write(ByteArray(0))
        assertEquals(listOf(513), endpoints.outLengths)
    }

    @Test
    fun `a failing empty packet throws`() {
        val endpoints = FakeEndpoints(outMaxPacketSize = 4).apply { outResults += listOf(4, -1) }
        assertThrows(IOException::class.java) { BulkPacketChannel(endpoints).write(ByteArray(4)) }
    }

    @Test
    fun `a read returns what the endpoint delivered`() {
        val endpoints = FakeEndpoints().apply {
            inResult = { buffer, offset, _ -> buffer[offset] = 7; 1 }
        }
        val buffer = ByteArray(4)
        assertEquals(1, BulkPacketChannel(endpoints).read(buffer, 2, 2))
        assertEquals(7, buffer[2].toInt())
    }

    @Test
    fun `a read timeout returns zero`() {
        var now = 0L
        val endpoints = FakeEndpoints().apply {
            inResult = { _, _, _ -> now += 1_000_000_000L; -1 }
        }
        assertEquals(0, BulkPacketChannel(endpoints, nanoTime = { now }).read(ByteArray(8), 0, 8))
    }

    @Test
    fun `a fast failure is a removed device`() {
        var now = 0L
        val endpoints = FakeEndpoints().apply {
            inResult = { _, _, _ -> now += 10_000_000L; -1 }
        }
        assertThrows(IOException::class.java) { BulkPacketChannel(endpoints, nanoTime = { now }).read(ByteArray(8), 0, 8) }
    }

    @Test
    fun `a read into an empty buffer does no transfer`() {
        val calls = AtomicInteger()
        val endpoints = FakeEndpoints().apply { inResult = { _, _, _ -> calls.incrementAndGet(); 1 } }
        assertEquals(0, BulkPacketChannel(endpoints).read(ByteArray(8), 0, 0))
        assertEquals(0, calls.get())
    }

    @Test
    fun `close is idempotent, fails later transfers and releases once`() {
        val endpoints = FakeEndpoints()
        val releases = AtomicInteger()
        val counting = object : BulkEndpoints by endpoints {
            override fun release() {
                releases.incrementAndGet()
                endpoints.release()
            }
        }
        val channel = BulkPacketChannel(counting)
        channel.close()
        channel.close()
        assertTrue(endpoints.released.await(5, TimeUnit.SECONDS))
        Thread.sleep(100)
        assertEquals(1, releases.get())
        assertThrows(IOException::class.java) { channel.read(ByteArray(1), 0, 1) }
        assertThrows(IOException::class.java) { channel.write(ByteArray(1)) }
    }

    @Test
    fun `the device is not released while a transfer is in flight`() {
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val endpoints = FakeEndpoints().apply {
            inResult = { _, _, _ ->
                entered.countDown()
                proceed.await(10, TimeUnit.SECONDS)
                -1
            }
        }
        val channel = BulkPacketChannel(endpoints)
        val reader = pool.submit<Int> { channel.read(ByteArray(8), 0, 8) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        channel.close()
        assertFalse(endpoints.released.await(300, TimeUnit.MILLISECONDS))
        proceed.countDown()
        // The blocked read ends (failing, as the channel is closed) and only then is it released.
        runCatching { reader.get(5, TimeUnit.SECONDS) }
        assertTrue(endpoints.released.await(5, TimeUnit.SECONDS))
    }
}

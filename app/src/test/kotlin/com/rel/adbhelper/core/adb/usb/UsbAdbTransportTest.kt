package com.rel.adbhelper.core.adb.usb

import com.rel.adbhelper.core.adb.AdbClient
import com.rel.adbhelper.core.adb.AdbResult
import com.rel.adbhelper.core.adb.AdbStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@Timeout(60)
class UsbAdbTransportTest {

    private val channels = CopyOnWriteArrayList<ScriptedAdbPeer>()
    private val awaiting = AtomicInteger()
    private val transports = mutableListOf<UsbAdbTransport>()

    private fun transport(): UsbAdbTransport =
        UsbAdbTransport(
            openChannel = { ScriptedAdbPeer().also { channels += it } },
            identity = FakeIdentity(),
            onAwaitingAuthorisation = { awaiting.incrementAndGet() },
        ).also { transports += it }

    @AfterEach
    fun tearDown() {
        transports.forEach { it.close() }
    }

    // Returns Unit on purpose: a @Test method that returns a value is silently not run by JUnit 5.
    private fun blocking(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) =
        runBlocking { withTimeout(30_000) { block() } }

    /** Plays the device for the connection attempt that [channels] has [index] + 1 entries for. */
    private fun ScriptedAdbPeer.accept() {
        expect(AdbWire.CNXN)
        send(deviceCnxn())
    }

    /** Waits (bounded) for the transport to have opened its [index]-th channel. */
    private fun channel(index: Int): ScriptedAdbPeer {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (channels.size <= index) {
            check(System.nanoTime() < deadline) { "channel $index was never opened" }
            Thread.sleep(5)
        }
        return channels[index]
    }

    private fun ScriptedAdbPeer.acceptStream(remoteId: Int = 100): Int {
        val open = expect(AdbWire.OPEN)
        send(AdbMessage.okay(remoteId, open.arg0))
        return open.arg0
    }

    @Test
    fun `connects lazily on the first open`() = blocking {
        val transport = transport()
        assertEquals(0, channels.size)
        val stream = async(Dispatchers.Default) { transport.open("shell:a") }
        channel(0).accept()
        channel(0).acceptStream()
        stream.await().close()
        assertEquals(1, channels.size)
    }

    @Test
    fun `reuses the connection while it is alive`() = blocking {
        val transport = transport()
        val first = async(Dispatchers.Default) { transport.open("shell:a") }
        channel(0).accept()
        channel(0).acceptStream(100)
        first.await()
        val second = async(Dispatchers.Default) { transport.open("shell:b") }
        channel(0).acceptStream(200)
        second.await()
        assertEquals(1, channels.size)
    }

    @Test
    fun `concurrent first opens share one connection attempt`() = blocking {
        val transport = transport()
        val opens = (1..3).map { async(Dispatchers.Default) { transport.open("shell:$it") } }
        channel(0).accept()
        repeat(3) { channel(0).acceptStream(100 + it) }
        opens.forEach { it.await().close() }
        assertEquals(1, channels.size)
    }

    @Test
    fun `reconnects after invalidate and the old streams die`() = blocking {
        val transport = transport()
        val first = async(Dispatchers.Default) { transport.open("shell:a") }
        channel(0).accept()
        channel(0).acceptStream()
        val oldStream = first.await()

        transport.invalidate()
        assertTrue(channel(0).awaitClosed())
        assertThrows(IOException::class.java) { runBlocking { oldStream.readChunk() } }

        val second = async(Dispatchers.Default) { transport.open("shell:b") }
        channel(1).accept()
        channel(1).acceptStream()
        second.await()
        assertEquals(2, channels.size)
    }

    @Test
    fun `reconnects after the connection died by itself`() = blocking {
        val transport = transport()
        val first = async(Dispatchers.Default) { transport.open("shell:a") }
        channel(0).accept()
        channel(0).acceptStream()
        first.await()

        channel(0).failReads()
        assertTrue(channel(0).awaitClosed())
        val second = async(Dispatchers.Default) { transport.open("shell:b") }
        channel(1).accept()
        channel(1).acceptStream()
        second.await()
    }

    @Test
    fun `a failed connection attempt is retried by the next open`() = blocking {
        val transport = transport()
        val first = async(Dispatchers.Default) { runCatching { transport.open("shell:a") } }
        channel(0).expect(AdbWire.CNXN)
        channel(0).failReads()
        assertTrue(first.await().isFailure)

        val second = async(Dispatchers.Default) { transport.open("shell:a") }
        channel(1).accept()
        channel(1).acceptStream()
        second.await()
    }

    @Test
    fun `a cancelled caller does not abort the attempt or start a second one`() = blocking {
        val transport = transport()
        val first = async(Dispatchers.Default) { transport.open("shell:a") }
        channel(0).expect(AdbWire.CNXN)
        first.cancelAndJoin()

        val second = async(Dispatchers.Default) { transport.open("shell:a") }
        Thread.sleep(100)
        assertEquals(1, channels.size)
        channel(0).send(deviceCnxn())
        channel(0).acceptStream()
        second.await()
        assertEquals(1, channels.size)
    }

    @Test
    fun `waiting for the user is reported and close ends it`() = blocking {
        val transport = transport()
        val connecting = async(Dispatchers.Default) { runCatching { transport.connect() } }
        val peer = channel(0)
        peer.expect(AdbWire.CNXN)
        peer.send(AdbMessage(AdbWire.AUTH, AdbWire.AUTH_TOKEN, 0, ByteArray(20)))
        peer.expect(AdbWire.AUTH)
        peer.send(AdbMessage(AdbWire.AUTH, AdbWire.AUTH_TOKEN, 0, ByteArray(20)))
        peer.expect(AdbWire.AUTH)
        while (awaiting.get() == 0) Thread.sleep(5)

        transport.close()
        assertTrue(connecting.await().isFailure)
        assertTrue(peer.awaitClosed())
        assertThrows(IOException::class.java) { runBlocking { transport.open("shell:a") } }
    }

    @Test
    fun `close ends the connection`() = blocking {
        val transport = transport()
        val opened = async(Dispatchers.Default) { transport.open("shell:a") }
        channel(0).accept()
        channel(0).acceptStream()
        opened.await()
        transport.close()
        assertTrue(channel(0).awaitClosed())
    }

    @Test
    fun `connect returns the same live connection each time`() = blocking {
        val transport = transport()
        val first = async(Dispatchers.Default) { transport.connect() }
        channel(0).accept()
        val connection = first.await()
        assertSame(connection, transport.connect())
        transport.invalidate()
        val next = async(Dispatchers.Default) { transport.connect() }
        channel(1).accept()
        assertNotSame(connection, next.await())
    }

    @Test
    fun `stream adapter returns chunks then null and cancellation interrupts a blocked read`() = blocking {
        val transport = transport()
        val opening = async(Dispatchers.Default) { transport.open("shell:a") }
        val peer = channel(0)
        peer.accept()
        val localId = peer.acceptStream(100)
        val stream: AdbStream = opening.await()

        val read = async(Dispatchers.Default) { stream.readChunk() }
        Thread.sleep(100)
        peer.send(AdbMessage.write(100, localId, "hi".toByteArray()))
        assertArrayEquals("hi".toByteArray(), read.await())

        val blockedRead = async(Dispatchers.Default) { stream.readChunk() }
        Thread.sleep(100)
        blockedRead.cancelAndJoin() // would hang forever if the blocked read ignored the interrupt
        assertTrue(blockedRead.isCancelled)

        // The stream is still usable afterwards.
        peer.send(AdbMessage.write(100, localId, "again".toByteArray()))
        assertArrayEquals("again".toByteArray(), stream.readChunk())
        peer.send(AdbMessage.close(100, localId))
        assertEquals(null, stream.readChunk())
    }

    @Test
    fun `stream close from another thread ends a blocked read with Stream closed`() = blocking {
        val transport = transport()
        val opening = async(Dispatchers.Default) { transport.open("shell:a") }
        channel(0).accept()
        channel(0).acceptStream()
        val stream = opening.await()
        val started = CompletableDeferred<Unit>()
        val read = async(Dispatchers.Default) {
            started.complete(Unit)
            runCatching { stream.readChunk() }
        }
        started.await()
        Thread.sleep(100)
        stream.close()
        val error = read.await().exceptionOrNull()
        assertTrue(error is IOException)
        assertEquals("Stream closed.", error!!.message)
    }

    @Test
    fun `AdbClient shell works end to end`() = blocking {
        val transport = transport()
        val client = AdbClient(transport)
        val result = async(Dispatchers.Default) { client.shell("echo hi") }
        val peer = channel(0)
        peer.accept()

        val open = peer.expect(AdbWire.OPEN)
        assertEquals("shell:echo hi; echo __ADBHELPER_EXIT__:\$?\u0000", String(open.payload))
        peer.send(AdbMessage.okay(100, open.arg0))
        peer.send(AdbMessage.write(100, open.arg0, "hi\n__ADBHELPER_EXIT__:0\n".toByteArray()))
        peer.expect(AdbWire.OKAY)

        assertEquals(AdbResult.Success("hi\n"), result.await())
        val close = peer.expect(AdbWire.CLSE) // the client closes its stream once it has the answer
        assertEquals(open.arg0, close.arg0)
    }

    @Test
    fun `AdbClient reports a non-zero exit code and a refused service`() = blocking {
        val transport = transport()
        val client = AdbClient(transport)
        val failed = async(Dispatchers.Default) { client.shell("false") }
        val peer = channel(0)
        peer.accept()
        val open = peer.expect(AdbWire.OPEN)
        peer.send(AdbMessage.okay(100, open.arg0))
        peer.send(AdbMessage.write(100, open.arg0, "__ADBHELPER_EXIT__:1\n".toByteArray()))
        assertTrue(failed.await() is AdbResult.CommandFailed)
        peer.expect(AdbWire.OKAY)

        val refused = async(Dispatchers.Default) { client.shell("nope") }
        peer.expect(AdbWire.CLSE)
        val open2 = peer.expect(AdbWire.OPEN)
        peer.send(AdbMessage.close(0, open2.arg0))
        assertTrue(refused.await() is AdbResult.TransportUnreachable)
    }
}

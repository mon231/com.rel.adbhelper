package com.rel.adbhelper.core.adb.usb

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@Timeout(60)
class AdbConnectionTest {

    private val pool = Executors.newCachedThreadPool { Thread(it, "test-worker").apply { isDaemon = true } }
    private val connections = mutableListOf<AdbConnection>()

    @AfterEach
    fun tearDown() {
        connections.forEach { it.close() }
        pool.shutdownNow()
    }

    private fun <T> async(block: () -> T): Future<T> = pool.submit<T> { block() }

    private fun <T> Future<T>.await(): T = get(5, TimeUnit.SECONDS)

    private fun <T> Future<T>.failure(): Throwable =
        try {
            get(5, TimeUnit.SECONDS)
            throw AssertionError("expected the call to fail")
        } catch (e: ExecutionException) {
            e.cause!!
        }

    private fun Future<*>.assertStillRunning() {
        try {
            get(200, TimeUnit.MILLISECONDS)
            throw AssertionError("expected the call to still be blocked")
        } catch (_: TimeoutException) {
        }
    }

    private fun connect(
        peer: ScriptedAdbPeer,
        identity: AdbIdentity = FakeIdentity(),
        onAwaiting: () -> Unit = {},
    ): Future<AdbConnection> = async { AdbConnection.connect(peer, identity, onAwaiting) }

    private fun connected(peer: ScriptedAdbPeer = ScriptedAdbPeer(), maxPayload: Int = 4096): AdbConnection {
        val future = connect(peer)
        peer.expect(AdbWire.CNXN)
        peer.send(deviceCnxn(maxPayload))
        return future.await().also { connections += it }
    }

    private class Opened(val stream: AdbConnectionStream, val localId: Int, val remoteId: Int)

    private fun openStream(
        connection: AdbConnection,
        peer: ScriptedAdbPeer,
        service: String = "shell:x",
        remoteId: Int = 100,
    ): Opened {
        val future = async { connection.open(service) }
        val open = peer.expect(AdbWire.OPEN)
        assertEquals(0, open.arg1)
        assertEquals(service + "\u0000", String(open.payload))
        peer.send(AdbMessage.okay(remoteId, open.arg0))
        return Opened(future.await(), open.arg0, remoteId)
    }

    // ---- handshake ----

    @Test
    fun `handshake without auth`() {
        val peer = ScriptedAdbPeer()
        val future = connect(peer)
        val cnxn = peer.expect(AdbWire.CNXN)
        assertEquals(AdbWire.VERSION, cnxn.arg0)
        assertEquals(AdbWire.MAX_PAYLOAD, cnxn.arg1)
        peer.send(deviceCnxn(maxPayload = 8192, banner = "device::ro.product.name=x"))
        val connection = future.await().also { connections += it }
        assertEquals(8192, connection.peerMaxPayload)
        assertEquals("device::ro.product.name=x", connection.peerBanner)
        assertTrue(connection.isAlive)
    }

    /** What a real adbd (Android 17 emulator) sends to a client that advertised version
     * 0x01000000: an AUTH token first, then, after the signature, CNXN with version 0x01000001,
     * max payload 0x40000, a long banner with `delayed_ack` in its features, and correct checksums
     * on every packet; then streams work as usual. */
    @Test
    fun `handshake like a real adbd with version 0x01000001 and a 256 KiB payload`() {
        val peer = ScriptedAdbPeer()
        val future = connect(peer)
        peer.expect(AdbWire.CNXN)
        peer.send(AdbMessage(AdbWire.AUTH, AdbWire.AUTH_TOKEN, 0, ByteArray(20) { it.toByte() }))
        assertEquals(AdbWire.AUTH_SIGNATURE, peer.expect(AdbWire.AUTH).arg0)
        val banner = "device::ro.product.name=sdk_gphone64_x86_64;ro.product.model=sdk_gphone64_x86_64;" +
            "ro.product.device=emu64xa;features=shell_v2,cmd,stat_v2,ls_v2,fixed_push_mkdir,apex,abb," +
            "abb_exec,remount_shell,track_app,sendrecv_v2,openscreen_mdns,devraw,app_info,delayed_ack"
        peer.send(deviceCnxn(maxPayload = 0x40000, version = 0x01000001, banner = banner))
        val connection = future.await().also { connections += it }
        assertEquals(0x40000, connection.peerMaxPayload)
        assertEquals(banner, connection.peerBanner)

        val opened = openStream(connection, peer)
        peer.send(AdbMessage.write(opened.remoteId, opened.localId, "hello\n".toByteArray()))
        assertEquals("hello\n", String(opened.stream.readChunk()!!))
    }

    @Test
    fun `handshake answers an AUTH token with a signature`() {
        val peer = ScriptedAdbPeer()
        var asked = false
        val future = connect(peer) { asked = true }
        peer.expect(AdbWire.CNXN)
        val token = ByteArray(20) { it.toByte() }
        peer.send(AdbMessage(AdbWire.AUTH, AdbWire.AUTH_TOKEN, 0, token))
        val auth = peer.expect(AdbWire.AUTH)
        assertEquals(AdbWire.AUTH_SIGNATURE, auth.arg0)
        assertArrayEquals("sig:".toByteArray() + token, auth.payload)
        peer.send(deviceCnxn())
        connections += future.await()
        assertFalse(asked)
    }

    @Test
    fun `handshake falls back to the public key and reports the wait for the user`() {
        val peer = ScriptedAdbPeer()
        val awaiting = CountDownLatch(1)
        val future = connect(peer) { awaiting.countDown() }
        peer.expect(AdbWire.CNXN)
        peer.send(AdbMessage(AdbWire.AUTH, AdbWire.AUTH_TOKEN, 0, ByteArray(20) { 1 }))
        assertEquals(AdbWire.AUTH_SIGNATURE, peer.expect(AdbWire.AUTH).arg0)
        peer.send(AdbMessage(AdbWire.AUTH, AdbWire.AUTH_TOKEN, 0, ByteArray(20) { 2 }))
        val key = peer.expect(AdbWire.AUTH)
        assertEquals(AdbWire.AUTH_RSAPUBLICKEY, key.arg0)
        assertArrayEquals("pubkey\u0000".toByteArray(), key.payload)
        assertTrue(awaiting.await(5, TimeUnit.SECONDS))
        future.assertStillRunning() // the user has not tapped Allow yet
        peer.send(deviceCnxn())
        connections += future.await()
    }

    @Test
    fun `a device advertising a maximum payload of 0 is refused and the channel closed`() {
        val peer = ScriptedAdbPeer()
        val future = connect(peer)
        peer.expect(AdbWire.CNXN)
        peer.send(deviceCnxn(maxPayload = 0))
        assertTrue(future.failure() is AdbProtocolException)
        assertTrue(peer.awaitClosed())
    }

    @Test
    fun `a huge advertised maximum payload is capped, also when it looks negative`() {
        assertEquals(AdbWire.MAX_PAYLOAD, connected(maxPayload = 0x7FFFFFFF).peerMaxPayload)
        assertEquals(AdbWire.MAX_PAYLOAD, connected(maxPayload = -1).peerMaxPayload)
        assertEquals(AdbWire.MAX_PAYLOAD, connected(maxPayload = AdbWire.MAX_PAYLOAD).peerMaxPayload)
        assertEquals(1, connected(maxPayload = 1).peerMaxPayload)
    }

    @Test
    fun `closing the channel aborts a handshake that waits for the user`() {
        val peer = ScriptedAdbPeer()
        val future = connect(peer)
        peer.expect(AdbWire.CNXN)
        peer.send(AdbMessage(AdbWire.AUTH, AdbWire.AUTH_TOKEN, 0, ByteArray(20)))
        peer.expect(AdbWire.AUTH)
        future.assertStillRunning()
        peer.close()
        assertTrue(future.failure() is IOException)
    }

    @Test
    fun `an unexpected AUTH type fails the handshake`() {
        val peer = ScriptedAdbPeer()
        val future = connect(peer)
        peer.expect(AdbWire.CNXN)
        peer.send(AdbMessage(AdbWire.AUTH, AdbWire.AUTH_SIGNATURE, 0, ByteArray(20)))
        assertTrue(future.failure() is AdbProtocolException)
        assertTrue(peer.awaitClosed())
    }

    @Test
    fun `an idle connection is not killed by reads that return no data`() {
        val peer = ScriptedAdbPeer(pollMillis = 1)
        val future = connect(peer)
        peer.expect(AdbWire.CNXN)
        // Far more empty reads than any "give up after N" rule would tolerate, mid-handshake.
        waitFor { peer.emptyReads.get() > 3_000 }
        peer.send(deviceCnxn())
        val connection = future.await().also { connections += it }
        val seen = peer.emptyReads.get()
        waitFor { peer.emptyReads.get() > seen + 3_000 }
        assertTrue(connection.isAlive)
        val opened = openStream(connection, peer)
        peer.send(AdbMessage.write(opened.remoteId, opened.localId, byteArrayOf(1)))
        assertArrayEquals(byteArrayOf(1), opened.stream.readChunk())
    }

    private fun waitFor(timeoutMillis: Long = 30_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (!condition()) {
            if (System.nanoTime() > deadline) throw AssertionError("condition not reached in time")
            Thread.sleep(5)
        }
    }

    // ---- streams ----

    @Test
    fun `open data and remote close deliver chunks then null and each chunk is acknowledged once consumed`() {
        val peer = ScriptedAdbPeer()
        val connection = connected(peer)
        val s = openStream(connection, peer)

        peer.send(AdbMessage.write(s.remoteId, s.localId, "one".toByteArray()))
        peer.expectSilence() // not acknowledged before the reader takes it
        assertArrayEquals("one".toByteArray(), s.stream.readChunk())
        val ack1 = peer.expect(AdbWire.OKAY)
        assertEquals(s.localId, ack1.arg0)
        assertEquals(s.remoteId, ack1.arg1)

        peer.send(AdbMessage.write(s.remoteId, s.localId, "two".toByteArray()))
        assertArrayEquals("two".toByteArray(), s.stream.readChunk())
        peer.expect(AdbWire.OKAY)

        peer.send(AdbMessage.close(s.remoteId, s.localId))
        assertNull(s.stream.readChunk())
        assertNull(s.stream.readChunk()) // stays at the end
        s.stream.close()
        peer.expectSilence() // the device closed first: nothing to say back
    }

    @Test
    fun `data that arrived before the remote close is still delivered`() {
        val peer = ScriptedAdbPeer()
        val s = openStream(connected(peer), peer)
        peer.send(AdbMessage.write(s.remoteId, s.localId, "tail".toByteArray()))
        peer.send(AdbMessage.close(s.remoteId, s.localId))
        assertArrayEquals("tail".toByteArray(), s.stream.readChunk())
        assertNull(s.stream.readChunk())
    }

    @Test
    fun `an empty WRTE is acknowledged immediately and not delivered`() {
        val peer = ScriptedAdbPeer()
        val s = openStream(connected(peer), peer)
        peer.send(AdbMessage.write(s.remoteId, s.localId, ByteArray(0)))
        val ack = peer.expect(AdbWire.OKAY)
        assertEquals(s.localId, ack.arg0)
        peer.send(AdbMessage.write(s.remoteId, s.localId, byteArrayOf(9)))
        assertArrayEquals(byteArrayOf(9), s.stream.readChunk())
    }

    @Test
    fun `a refused OPEN throws`() {
        val peer = ScriptedAdbPeer()
        val connection = connected(peer)
        val future = async { connection.open("nonsense:") }
        val open = peer.expect(AdbWire.OPEN)
        peer.send(AdbMessage.close(0, open.arg0))
        val error = future.failure()
        assertTrue(error is IOException)
        assertTrue(error.message!!.contains("refused"), error.message)
        assertTrue(connection.isAlive)
    }

    @Test
    fun `an OKAY that arrives after open was abandoned is answered with CLSE`() {
        val peer = ScriptedAdbPeer()
        val connection = connected(peer)
        val future = async { connection.open("shell:slow") }
        val open = peer.expect(AdbWire.OPEN)
        future.assertStillRunning()
        // Interrupt the blocked open: the worker gives up.
        future.cancel(true)
        peer.expectSilence(100)
        peer.send(AdbMessage.okay(77, open.arg0))
        val close = peer.expect(AdbWire.CLSE)
        assertEquals(open.arg0, close.arg0)
        assertEquals(77, close.arg1)
    }

    @Test
    fun `a write larger than the peer's maximum payload is split and waits for each OKAY`() {
        val peer = ScriptedAdbPeer()
        val s = openStream(connected(peer, maxPayload = 4), peer)
        val data = ByteArray(10) { it.toByte() }
        val write = async { s.stream.write(data) }

        val first = peer.expect(AdbWire.WRTE)
        assertArrayEquals(data.copyOfRange(0, 4), first.payload)
        assertEquals(s.localId, first.arg0)
        assertEquals(s.remoteId, first.arg1)
        peer.expectSilence() // no second packet before the OKAY
        peer.send(AdbMessage.okay(s.remoteId, s.localId))

        assertArrayEquals(data.copyOfRange(4, 8), peer.expect(AdbWire.WRTE).payload)
        peer.expectSilence()
        peer.send(AdbMessage.okay(s.remoteId, s.localId))

        assertArrayEquals(data.copyOfRange(8, 10), peer.expect(AdbWire.WRTE).payload)
        write.assertStillRunning() // not done until the last packet is acknowledged
        peer.send(AdbMessage.okay(s.remoteId, s.localId))
        write.await()
    }

    @Test
    fun `local close unblocks a blocked readChunk without blocking and tells the device`() {
        val peer = ScriptedAdbPeer()
        val s = openStream(connected(peer), peer)
        val read = async { s.stream.readChunk() }
        read.assertStillRunning()

        val started = System.nanoTime()
        s.stream.close()
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_000)

        val error = read.failure()
        assertTrue(error is IOException)
        assertEquals("Stream closed.", error.message)
        val close = peer.expect(AdbWire.CLSE)
        assertEquals(s.localId, close.arg0)
        assertEquals(s.remoteId, close.arg1)

        s.stream.close() // idempotent: no second CLSE
        peer.expectSilence()
        assertEquals("Stream closed.", assertThrows(IOException::class.java) { s.stream.readChunk() }.message)
        assertEquals("Stream closed.", assertThrows(IOException::class.java) { s.stream.write(byteArrayOf(1)) }.message)
    }

    @Test
    fun `local close unblocks a write waiting for OKAY`() {
        val peer = ScriptedAdbPeer()
        val s = openStream(connected(peer), peer)
        val write = async { s.stream.write(byteArrayOf(1, 2, 3)) }
        peer.expect(AdbWire.WRTE)
        write.assertStillRunning()
        s.stream.close()
        assertEquals("Stream closed.", write.failure().message)
    }

    @Test
    fun `an interrupted readChunk leaves the stream usable`() {
        val peer = ScriptedAdbPeer()
        val s = openStream(connected(peer), peer)
        val read = async { s.stream.readChunk() }
        read.assertStillRunning()
        read.cancel(true)
        peer.send(AdbMessage.write(s.remoteId, s.localId, "later".toByteArray()))
        assertArrayEquals("later".toByteArray(), s.stream.readChunk())
    }

    @Test
    fun `two interleaved streams get their own data and their own writes`() {
        val peer = ScriptedAdbPeer()
        val connection = connected(peer)
        val a = openStream(connection, peer, "shell:a", remoteId = 100)
        val b = openStream(connection, peer, "shell:b", remoteId = 200)
        assertTrue(a.localId != b.localId)

        peer.send(AdbMessage.write(b.remoteId, b.localId, "for-b".toByteArray()))
        peer.send(AdbMessage.write(a.remoteId, a.localId, "for-a".toByteArray()))
        assertArrayEquals("for-a".toByteArray(), a.stream.readChunk())
        assertArrayEquals("for-b".toByteArray(), b.stream.readChunk())
        val acks = listOf(peer.expect(AdbWire.OKAY), peer.expect(AdbWire.OKAY))
        assertEquals(setOf(a.localId to a.remoteId, b.localId to b.remoteId), acks.map { it.arg0 to it.arg1 }.toSet())

        val writeA = async { a.stream.write("x".toByteArray()) }
        val msgA = peer.expect(AdbWire.WRTE)
        assertEquals(a.remoteId, msgA.arg1)
        peer.send(AdbMessage.okay(a.remoteId, a.localId))
        writeA.await()

        // Closing one leaves the other working.
        peer.send(AdbMessage.close(a.remoteId, a.localId))
        assertNull(a.stream.readChunk())
        peer.send(AdbMessage.write(b.remoteId, b.localId, "still".toByteArray()))
        assertArrayEquals("still".toByteArray(), b.stream.readChunk())
    }

    @Test
    fun `concurrent opens each get a distinct stream`() {
        val peer = ScriptedAdbPeer()
        val connection = connected(peer)
        val futures = (1..4).map { i -> async { connection.open("shell:$i") } }
        val opens = (1..4).map { peer.expect(AdbWire.OPEN) }
        assertEquals(4, opens.map { it.arg0 }.toSet().size)
        opens.forEachIndexed { i, open -> peer.send(AdbMessage.okay(500 + i, open.arg0)) }
        futures.forEach { it.await() }
    }

    // ---- failure ----

    @Test
    fun `channel failure fails every stream and every later open`() {
        val peer = ScriptedAdbPeer()
        val connection = connected(peer)
        val a = openStream(connection, peer, remoteId = 100)
        val b = openStream(connection, peer, remoteId = 200)
        val readA = async { a.stream.readChunk() }
        val writeB = async { b.stream.write(byteArrayOf(1)) }
        peer.expect(AdbWire.WRTE)
        readA.assertStillRunning()

        peer.failReads(IOException("cable pulled"))
        assertTrue(readA.failure() is IOException)
        assertTrue(writeB.failure() is IOException)
        assertFalse(connection.isAlive)
        assertTrue(peer.awaitClosed())
        assertThrows(IOException::class.java) { connection.open("shell:later") }
        assertThrows(IOException::class.java) { a.stream.readChunk() }
        a.stream.close() // must not throw or block
    }

    @Test
    fun `buffered data is still readable after the connection died`() {
        val peer = ScriptedAdbPeer()
        val s = openStream(connected(peer), peer)
        peer.send(AdbMessage.write(s.remoteId, s.localId, "last words".toByteArray()))
        peer.failReads()
        Thread.sleep(100)
        assertArrayEquals("last words".toByteArray(), s.stream.readChunk())
        assertThrows(IOException::class.java) { s.stream.readChunk() }
    }

    @Test
    fun `a corrupt packet kills the connection`() {
        val peer = ScriptedAdbPeer()
        val connection = connected(peer)
        val s = openStream(connection, peer)
        peer.sendRaw(ByteArray(AdbWire.HEADER_LENGTH) { 0x55 })
        assertThrows(IOException::class.java) { while (true) s.stream.readChunk() }
        waitFor { !connection.isAlive }
    }

    @Test
    fun `a CNXN in mid session kills the connection`() {
        val peer = ScriptedAdbPeer()
        val connection = connected(peer)
        val s = openStream(connection, peer)
        val read = async { s.stream.readChunk() }
        read.assertStillRunning()
        peer.send(deviceCnxn())
        assertTrue(read.failure() is IOException)
        assertFalse(connection.isAlive)
        assertTrue(peer.awaitClosed())
    }

    @Test
    fun `a packet for an unknown stream is answered with CLSE`() {
        val peer = ScriptedAdbPeer()
        connected(peer)
        peer.send(AdbMessage.write(55, 99, "x".toByteArray()))
        val close = peer.expect(AdbWire.CLSE)
        assertEquals(99, close.arg0)
        assertEquals(55, close.arg1)

        peer.send(AdbMessage.okay(55, 98))
        val close2 = peer.expect(AdbWire.CLSE)
        assertEquals(98, close2.arg0)
    }

    @Test
    fun `a CLSE for an unknown stream is not answered`() {
        val peer = ScriptedAdbPeer()
        val connection = connected(peer)
        peer.send(AdbMessage.close(55, 99))
        peer.expectSilence()
        assertTrue(connection.isAlive)
    }

    @Test
    fun `a packet for a stream closed locally is answered with CLSE`() {
        val peer = ScriptedAdbPeer()
        val s = openStream(connected(peer), peer)
        s.stream.close()
        peer.expect(AdbWire.CLSE)
        peer.send(AdbMessage.write(s.remoteId, s.localId, "late".toByteArray()))
        val close = peer.expect(AdbWire.CLSE)
        assertEquals(s.localId, close.arg0)
        assertEquals(s.remoteId, close.arg1)
    }

    @Test
    fun `close ends the session and fails open streams`() {
        val peer = ScriptedAdbPeer()
        val connection = connected(peer)
        val s = openStream(connection, peer)
        val read = async { s.stream.readChunk() }
        read.assertStillRunning()
        connection.close()
        assertTrue(read.failure() is IOException)
        assertTrue(peer.awaitClosed())
        assertFalse(connection.isAlive)
        connection.close() // idempotent
    }
}

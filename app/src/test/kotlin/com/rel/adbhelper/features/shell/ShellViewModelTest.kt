package com.rel.adbhelper.features.shell

import com.rel.adbhelper.core.adb.AdbClient
import com.rel.adbhelper.core.adb.AdbStream
import com.rel.adbhelper.core.adb.AdbTransport
import com.rel.adbhelper.core.adb.FakeAdbTransport
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch

private class RecordingStream(private val writeFailure: Throwable? = null) : AdbStream {
    @Volatile var closedOnThread: Thread? = null
    override suspend fun write(data: ByteArray) {
        writeFailure?.let { throw it }
    }

    // Never resolves on its own — a real terminal session's reader loop sits here until the
    // stream is closed out from under it. If this returned immediately instead, the shell
    // session's own reader loop would null out `stream` before restart() ever got a chance to
    // close it, and the test would pass for the wrong reason (close() skipped as a no-op, not
    // exercised at all).
    override suspend fun readChunk(): ByteArray? = awaitCancellation()
    override fun close() {
        closedOnThread = Thread.currentThread()
    }
}

private class RecordingTransport(val stream: RecordingStream) : AdbTransport {
    override suspend fun open(service: String): AdbStream = stream
}

/**
 * Models the real `io.github.muntashirakon.adb.AdbStream`'s documented behavior when forced
 * closed while a read is blocked on it: the blocked read throws `IOException("Stream closed.")`
 * rather than the coroutine simply being cancelled cleanly. [awaitCancellation]-based fixtures
 * (like [RecordingStream] above) respond to plain cancellation and so never exercise this path —
 * this needs a real blocking wait a real `close()` call unblocks, same reasoning as
 * `FakeAdbTransport`'s `neverSignalsEof` fixture in `AdbClientTest`.
 */
private class ThrowsOnForceCloseStream : AdbStream {
    private val latch = CountDownLatch(1)
    override suspend fun write(data: ByteArray) {}
    override suspend fun readChunk(): ByteArray? {
        withContext(Dispatchers.IO) { latch.await() }
        throw IOException("Stream closed.")
    }
    override fun close() {
        latch.countDown()
    }
}

private class SingleStreamTransport(private val stream: AdbStream) : AdbTransport {
    override suspend fun open(service: String): AdbStream = stream
}

/** Ends its read normally, but only [lateByMs] after it is closed — so the old reader finishes
 * after restart() has already started the next session. */
private class EndsLateOnCloseStream(private val lateByMs: Long) : AdbStream {
    private val latch = CountDownLatch(1)
    override suspend fun write(data: ByteArray) {}
    override suspend fun readChunk(): ByteArray? {
        withContext(Dispatchers.IO) {
            latch.await()
            Thread.sleep(lateByMs)
        }
        return null
    }
    override fun close() {
        latch.countDown()
    }
}

private class SequenceTransport(private val streams: List<AdbStream>) : AdbTransport {
    private var next = 0
    override suspend fun open(service: String): AdbStream = streams[next++]
}

class ShellViewModelTest {

    @Test
    fun `a late-finishing old reader does not orphan the restarted session`() {
        val second = RecordingStream()
        val vm = ShellViewModel(
            AdbClient(SequenceTransport(listOf(EndsLateOnCloseStream(300), second)), watchdogDispatcher = Dispatchers.Unconfined),
            scope = CoroutineScope(Dispatchers.Unconfined),
            closeDispatcher = Dispatchers.IO,
        )
        vm.start()
        assertTrue(vm.state.value.connected)

        vm.restart()
        Thread.sleep(700)

        assertTrue(vm.state.value.connected, "new session was marked ended: ${vm.state.value}")
        assertEquals("", vm.state.value.message)
        // The new stream is still the current one: closing the view model closes it.
        vm.close()
        val deadline = System.currentTimeMillis() + 2_000
        while (second.closedOnThread == null && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(second.closedOnThread != null, "expected the new stream to still be tracked")
    }

    @Test
    fun `restartAdbServer reports success when the restart drops the link`() {
        val vm = ShellViewModel(
            AdbClient(FakeAdbTransport(throwOnRead = IOException("Stream closed.")), watchdogDispatcher = Dispatchers.Unconfined),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        vm.restartAdbServer()

        assertEquals("adb server restart requested", vm.state.value.message)
    }

    @Test
    fun `restartAdbServer reports a command the target rejected`() {
        val vm = ShellViewModel(
            AdbClient(
                FakeAdbTransport(scriptedOutput = "Failed to set property\n", exitCode = 1),
                watchdogDispatcher = Dispatchers.Unconfined,
            ),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        vm.restartAdbServer()

        assertTrue(vm.state.value.message.startsWith("Failed to restart adb server"), "got ${vm.state.value.message}")
    }

    @Test
    fun `output keeps only the newest entries`() {
        // One byte per chunk: five more chunks than the cap, then the stream ends.
        val transport = FakeAdbTransport(
            scriptedOutput = "a".repeat(MAX_OUTPUT_ENTRIES) + "bbbbb",
            omitExitMarker = true,
            chunkSize = 1,
        )
        val vm = ShellViewModel(
            AdbClient(transport, watchdogDispatcher = Dispatchers.Unconfined),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        vm.start()

        val output = vm.state.value.output
        assertEquals(MAX_OUTPUT_ENTRIES, output.size)
        assertEquals(listOf("b", "b", "b", "b", "b"), output.takeLast(5))
    }

    @Test
    fun `restart closes the stream off the calling thread, not inline`() {
        // Live-reproduced: AdbStream.close() sends a real CLSE packet over the socket despite
        // being exposed as a plain non-suspend function. Calling it straight from restart() on
        // whatever thread invoked it (the UI thread, when wired from the "Restart terminal"
        // button) crashed with android.os.NetworkOnMainThreadException.
        //
        // Deliberately plain runBlocking, not runTest — this ViewModel's real default scope is
        // CoroutineScope(Dispatchers.Unconfined), a genuine dispatcher with no virtual-time
        // scheduler to advance. runTest's TestScope (and its backgroundScope) share one
        // TestCoroutineScheduler that every child launch must be visible to; a coroutine
        // launched here on a real Dispatchers.IO thread never registered with that scheduler at
        // all, so advanceUntilIdle() returned without ever running it. Real dispatchers need a
        // real wait, not a virtual-time one.
        val stream = RecordingStream()
        val transport = RecordingTransport(stream)
        val callingThread = Thread.currentThread()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val vm = ShellViewModel(
            AdbClient(transport, watchdogDispatcher = Dispatchers.Unconfined),
            scope = scope,
            closeDispatcher = Dispatchers.IO,
        )

        vm.start()
        val connectedDeadline = System.currentTimeMillis() + 2_000
        while (!vm.state.value.connected && System.currentTimeMillis() < connectedDeadline) {
            Thread.sleep(10)
        }
        assertTrue(vm.state.value.connected, "expected the shell session to connect, got ${vm.state.value}")

        vm.restart()
        val closeDeadline = System.currentTimeMillis() + 2_000
        while (stream.closedOnThread == null && System.currentTimeMillis() < closeDeadline) {
            Thread.sleep(10)
        }

        assertTrue(stream.closedOnThread != null, "expected close() to have been called")
        assertFalse(stream.closedOnThread === callingThread, "expected close() to run off the calling thread")
    }

    @Test
    fun `restart does not crash when the old reader's blocked read throws Stream closed`() {
        // Live-reproduced on the "Restart terminal" button, right after fixing the
        // NetworkOnMainThreadException above: once restart() correctly closed the old stream
        // off-thread, the old reader coroutine's pending readChunk() call — exactly as the real
        // library behaves when forced closed mid-read — threw IOException("Stream closed."),
        // uncaught, crashing the whole app. start()'s reader loop had no try/catch around
        // readChunk() at all.
        var uncaught: Throwable? = null
        val scope = CoroutineScope(Dispatchers.Unconfined + CoroutineExceptionHandler { _, e -> uncaught = e })
        val transport = SingleStreamTransport(ThrowsOnForceCloseStream())
        val vm = ShellViewModel(
            AdbClient(transport, watchdogDispatcher = Dispatchers.Unconfined),
            scope = scope,
            closeDispatcher = Dispatchers.IO,
        )

        vm.start()
        val connectedDeadline = System.currentTimeMillis() + 2_000
        while (!vm.state.value.connected && System.currentTimeMillis() < connectedDeadline) {
            Thread.sleep(10)
        }
        assertTrue(vm.state.value.connected, "expected the shell session to connect, got ${vm.state.value}")

        vm.restart()
        val settleDeadline = System.currentTimeMillis() + 2_000
        while (uncaught == null && System.currentTimeMillis() < settleDeadline) {
            Thread.sleep(10)
        }

        assertNull(uncaught, "expected no uncaught exception from the old reader's forced close, got $uncaught")
    }

    @Test
    fun `close closes the stream off the calling thread`() {
        // Leaving the Shell tab used to leak the session: nothing ever closed the stream.
        val stream = RecordingStream()
        val callingThread = Thread.currentThread()
        val vm = ShellViewModel(
            AdbClient(RecordingTransport(stream), watchdogDispatcher = Dispatchers.Unconfined),
            scope = CoroutineScope(Dispatchers.Unconfined),
            closeDispatcher = Dispatchers.IO,
        )
        vm.start()
        assertTrue(vm.state.value.connected, "expected the shell session to connect, got ${vm.state.value}")

        vm.close()
        val closeDeadline = System.currentTimeMillis() + 2_000
        while (stream.closedOnThread == null && System.currentTimeMillis() < closeDeadline) {
            Thread.sleep(10)
        }

        assertTrue(stream.closedOnThread != null, "expected close() to have been called")
        assertFalse(stream.closedOnThread === callingThread, "expected close() to run off the calling thread")
    }

    @Test
    fun `send on a dead stream marks the session ended instead of crashing`() {
        var uncaught: Throwable? = null
        val scope = CoroutineScope(Dispatchers.Unconfined + CoroutineExceptionHandler { _, e -> uncaught = e })
        val vm = ShellViewModel(
            AdbClient(RecordingTransport(RecordingStream(writeFailure = IOException("Stream closed."))), watchdogDispatcher = Dispatchers.Unconfined),
            scope = scope,
        )
        vm.start()
        assertTrue(vm.state.value.connected, "expected the shell session to connect, got ${vm.state.value}")

        vm.send("ls")

        assertNull(uncaught, "expected the failed write to be handled, got $uncaught")
        assertFalse(vm.state.value.connected)
        assertTrue(vm.state.value.message.isNotEmpty(), "expected a message explaining the session ended")
    }
}

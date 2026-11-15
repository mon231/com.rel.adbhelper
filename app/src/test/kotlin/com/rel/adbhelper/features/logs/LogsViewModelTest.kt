package com.rel.adbhelper.features.logs

import com.rel.adbhelper.core.adb.AdbClient
import com.rel.adbhelper.core.adb.AdbStream
import com.rel.adbhelper.core.adb.AdbTransport
import com.rel.adbhelper.core.adb.FakeAdbTransport
import com.rel.adbhelper.core.logs.filterLogLines
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * A stream the test feeds chunk by chunk. Like the real library's stream, a read is a genuinely
 * blocking wait that coroutine cancellation cannot interrupt: only [close] ends it, by making
 * the blocked read throw `IOException("Stream closed.")`.
 */
private class ScriptedStream : AdbStream {
    private val queue = LinkedBlockingQueue<Any>()
    val closeCount = AtomicInteger()
    val readsInFlight = AtomicInteger()
    val readsStarted = AtomicInteger()
    @Volatile var closedOnThread: Thread? = null

    fun feed(bytes: ByteArray) = queue.put(bytes)
    fun feed(text: String) = feed(text.toByteArray(Charsets.UTF_8))
    fun fail(error: Throwable) = queue.put(error)

    override suspend fun write(data: ByteArray) {}
    override suspend fun readAll(): ByteArray = ByteArray(0)
    override suspend fun readChunk(): ByteArray? {
        readsInFlight.incrementAndGet()
        readsStarted.incrementAndGet()
        try {
            return when (val next = withContext(Dispatchers.IO) { queue.take() }) {
                is Throwable -> throw next
                else -> next as ByteArray
            }
        } finally {
            readsInFlight.decrementAndGet()
        }
    }

    override fun close() {
        closeCount.incrementAndGet()
        closedOnThread = Thread.currentThread()
        queue.put(IOException("Stream closed."))
    }
}

private class ScriptedTransport(private val stream: AdbStream) : AdbTransport {
    @Volatile var openedService: String? = null
    override suspend fun open(service: String): AdbStream {
        openedService = service
        return stream
    }
}

private fun line(n: Int, level: Char = 'I') = "11-15 09:41:02.118  1423  1479 $level Tag: line $n\n"

class LogsViewModelTest {

    // Real dispatchers and real blocking reads, so real (bounded) waits — same reasoning as
    // ShellViewModelTest's note on runBlocking vs runTest.
    private fun awaitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertTrue(condition(), "timed out waiting for: $what")
    }

    private var uncaught: Throwable? = null
    private val stream = ScriptedStream()
    private val transport = ScriptedTransport(stream)

    private val viewModels = mutableListOf<LogsViewModel>()

    private fun viewModel(transport: AdbTransport = this.transport) = LogsViewModel(
        AdbClient(transport),
        scope = CoroutineScope(Dispatchers.Unconfined + CoroutineExceptionHandler { _, e -> uncaught = e }),
    ).also { viewModels += it }

    /** Unblocks each test's reader so no IO thread is left parked in a read. */
    @AfterEach
    fun closeViewModels() = viewModels.forEach { it.close() }

    private fun started(): LogsViewModel = viewModel().also { vm ->
        vm.start()
        awaitUntil("logcat stream to open") { vm.state.value.running }
    }

    @Test
    fun `start opens a threadtime logcat tail`() {
        started()
        assertEquals("shell:logcat -v threadtime -T 200", transport.openedService)
    }

    @Test
    fun `a line split across chunks, even mid-character, is emitted once and whole`() {
        val vm = started()
        val bytes = "11-15 09:41:02.118  1423  1479 I Tag: héllo wörld\r\n11-15 09:41:02.200  1423  1479 D Tag: par"
            .toByteArray(Charsets.UTF_8)
        // Cut between the two bytes of 'é', then again between the two bytes of 'ö'.
        val firstCut = bytes.indexOfFirst { it < 0 } + 1
        val secondCut = firstCut + 7

        stream.feed(bytes.copyOfRange(0, firstCut))
        stream.feed(bytes.copyOfRange(firstCut, secondCut))
        stream.feed(bytes.copyOfRange(secondCut, bytes.size))
        awaitUntil("the first whole line") { vm.state.value.lines.isNotEmpty() }

        // The second line has no newline yet, so it must still be held back.
        assertEquals(listOf("héllo wörld"), vm.state.value.lines.map { it.message })
        assertEquals("Tag", vm.state.value.lines.single().tag)

        stream.feed("tial\n")
        awaitUntil("the second line") { vm.state.value.lines.size == 2 }
        assertEquals(listOf("héllo wörld", "partial"), vm.state.value.lines.map { it.message })
        assertEquals(vm.state.value.lines, vm.state.value.visible)
    }

    @Test
    fun `the buffer keeps only the newest lines and the filtered view stays in step`() {
        val vm = started()
        vm.setMinLevel('W')
        val total = LogsViewModel.MAX_LINES + 250
        // Every third line is a warning; fed in uneven chunks so trimming happens repeatedly.
        (0 until total).chunked(700).forEach { batch ->
            stream.feed(batch.joinToString("") { line(it, if (it % 3 == 0) 'W' else 'I') })
        }
        awaitUntil("the last line") { vm.state.value.lines.lastOrNull()?.message == "line ${total - 1}" }

        val state = vm.state.value
        assertEquals(LogsViewModel.MAX_LINES, state.lines.size)
        assertEquals("line 250", state.lines.first().message)
        assertEquals(filterLogLines(state.lines, 'W', ""), state.visible)
        assertTrue(state.visible.isNotEmpty() && state.visible.all { it.level == 'W' })
    }

    @Test
    fun `changing the level or query refilters what is already buffered`() {
        val vm = started()
        stream.feed(line(1, 'D') + line(2, 'E') + line(3, 'E'))
        awaitUntil("three lines") { vm.state.value.lines.size == 3 }

        vm.setMinLevel('E')
        assertEquals(listOf("line 2", "line 3"), vm.state.value.visible.map { it.message })
        vm.setQuery("LINE 3")
        assertEquals(listOf("line 3"), vm.state.value.visible.map { it.message })
        vm.setQuery("")
        vm.setMinLevel('V')
        assertEquals(3, vm.state.value.visible.size)
    }

    @Test
    fun `pause discards incoming lines, resume appends again, clear empties the buffer`() {
        val vm = started()
        stream.feed(line(1))
        awaitUntil("first line") { vm.state.value.lines.size == 1 }

        vm.pause()
        val readsBefore = stream.readsStarted.get()
        stream.feed(line(2))
        // A discarded line changes no state; the reader asking for its next chunk is the sign
        // that line 2 has been consumed.
        awaitUntil("the paused line to be consumed") { stream.readsStarted.get() > readsBefore }
        assertEquals(1, vm.state.value.lines.size)

        vm.resume()
        stream.feed(line(4))
        awaitUntil("line after resume") { vm.state.value.lines.lastOrNull()?.message == "line 4" }
        assertEquals(listOf("line 1", "line 4"), vm.state.value.lines.map { it.message })

        vm.clear()
        assertTrue(vm.state.value.lines.isEmpty() && vm.state.value.visible.isEmpty())
    }

    @Test
    fun `close ends the reader and closes the stream once, off the calling thread`() {
        val vm = started()
        awaitUntil("reader to block in a read") { stream.readsInFlight.get() == 1 }

        vm.close()
        vm.close()
        vm.stop()

        awaitUntil("the blocked read to end") { stream.readsInFlight.get() == 0 }
        assertEquals(1, stream.closeCount.get())
        assertNotSame(Thread.currentThread(), stream.closedOnThread)
        // The forced close surfaces in the reader as IOException("Stream closed."): it must be
        // neither a crash nor a user-facing message.
        Thread.sleep(50)
        assertNull(uncaught)
        assertFalse(vm.state.value.running)
        assertEquals("", vm.state.value.message)
    }

    @Test
    fun `close before start is harmless`() {
        viewModel().close()
        assertEquals(0, stream.closeCount.get())
    }

    @Test
    fun `a stream error ends in a message and a closed stream, not a crash`() {
        val vm = started()
        stream.feed(line(1))
        stream.fail(IOException("connection reset"))

        awaitUntil("the session to end") { !vm.state.value.running }
        awaitUntil("the stream to be closed") { stream.closeCount.get() == 1 }
        assertEquals("Logcat ended", vm.state.value.message)
        assertEquals(1, vm.state.value.lines.size)
        assertNull(uncaught)
    }

    @Test
    fun `a failed open ends in a message, not a crash`() {
        val vm = viewModel(FakeAdbTransport(throwOnOpen = IOException("unreachable")))
        vm.start()
        awaitUntil("the failure message") { vm.state.value.message.isNotEmpty() }
        assertEquals("Could not open logcat", vm.state.value.message)
        assertFalse(vm.state.value.running)
        assertNull(uncaught)
    }
}

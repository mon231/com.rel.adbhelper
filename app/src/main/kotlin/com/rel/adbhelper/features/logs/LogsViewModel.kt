package com.rel.adbhelper.features.logs

import com.rel.adbhelper.core.adb.AdbClient
import com.rel.adbhelper.core.adb.AdbStream
import com.rel.adbhelper.core.logs.LogLine
import com.rel.adbhelper.core.logs.filterLogLines
import com.rel.adbhelper.core.logs.matches
import com.rel.adbhelper.core.logs.parseLogLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference

class LogsViewModel(
    private val client: AdbClient,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
    /** Exposed for tests only — [AdbStream.close] is blocking network I/O and must never run on
     * the main thread (see `ShellViewModel.restart`'s doc), so production always uses IO. */
    private val closeDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    data class State(
        /** Newest [MAX_LINES] lines, unfiltered. */
        val lines: List<LogLine> = emptyList(),
        /** [lines] narrowed by [minLevel] and [query] — what the screen shows. */
        val visible: List<LogLine> = emptyList(),
        /** The remote `logcat` stream is open. */
        val running: Boolean = false,
        /** Incoming lines are discarded rather than appended, so the list holds still. */
        val paused: Boolean = false,
        val minLevel: Char = 'V',
        val query: String = "",
        val message: String = "",
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    // State is written from both the reader (an IO thread) and UI actions (main), hence
    // `update {}` everywhere and an atomic handoff of the stream between reader and close().
    private val stream = AtomicReference<AdbStream?>(null)
    private var reader: Job? = null

    fun start() {
        if (reader?.isActive == true) return
        reader = scope.launch {
            var opened: AdbStream? = null
            try {
                opened = client.openService(SERVICE)
                stream.set(opened)
                // close() may have run while the open was in flight, finding no stream to close;
                // without this the first blocking read below would never be interrupted.
                ensureActive()
                _state.update { it.copy(running = true, message = "") }
                readLines(opened)
                ensureActive() // EOF caused by our own close() is not worth a message either.
                _state.update { it.copy(running = false, message = "Logcat ended") }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                // A stream closed out from under a blocked read throws IOException("Stream
                // closed.") instead of honouring cancellation — after close() that is the
                // expected way for this reader to end, not something to report.
                if (isActive) {
                    val message = if (opened == null) "Could not open logcat" else "Logcat ended"
                    _state.update { it.copy(running = false, message = message) }
                }
            } finally {
                closeOffThread(opened)
            }
        }
    }

    /** Ends the remote `logcat`. Safe to call repeatedly and before [start]. */
    fun close() {
        reader?.cancel()
        reader = null
        // Cancellation alone cannot interrupt a blocked read; closing the stream does.
        closeOffThread(stream.get())
        _state.update { it.copy(running = false) }
    }

    fun stop() = close()

    fun pause() = _state.update { it.copy(paused = true) }

    fun resume() = _state.update { it.copy(paused = false) }

    fun clear() = _state.update { it.copy(lines = emptyList(), visible = emptyList()) }

    fun setMinLevel(level: Char) =
        _state.update { it.copy(minLevel = level, visible = filterLogLines(it.lines, level, it.query)) }

    fun setQuery(query: String) =
        _state.update { it.copy(query = query, visible = filterLogLines(it.lines, it.minLevel, query)) }

    /** Closes [s] at most once across the reader and [close], whichever gets there first. */
    private fun closeOffThread(s: AdbStream?) {
        if (s != null && stream.compareAndSet(s, null)) scope.launch(closeDispatcher) { s.close() }
    }

    /**
     * Chunks arrive at arbitrary byte boundaries, so bytes are held back until a newline and
     * only whole lines are decoded — a line, or a multi-byte UTF-8 character, split across two
     * chunks is never decoded in halves.
     */
    private suspend fun readLines(s: AdbStream) {
        val pending = ByteArrayOutputStream()
        while (true) {
            val chunk = s.readChunk() ?: return
            val lastNewline = chunk.lastIndexOf('\n'.code.toByte())
            if (lastNewline < 0) {
                pending.write(chunk)
                continue
            }
            pending.write(chunk, 0, lastNewline)
            val text = pending.toString(Charsets.UTF_8.name())
            pending.reset()
            pending.write(chunk, lastNewline + 1, chunk.size - lastNewline - 1)
            append(text.split('\n').map { it.removeSuffix("\r") }.filter { it.isNotEmpty() }.map(::parseLogLine))
        }
    }

    /** Appends to both lists and trims both to the newest [MAX_LINES] of [State.lines], filtering
     * only the new lines and the ones being dropped rather than the whole buffer. */
    private fun append(new: List<LogLine>) = _state.update { s ->
        if (s.paused || new.isEmpty()) return@update s
        val all = s.lines + new
        val dropped = all.subList(0, (all.size - MAX_LINES).coerceAtLeast(0))
        val nowVisible = s.visible + filterLogLines(new, s.minLevel, s.query)
        s.copy(
            lines = all.drop(dropped.size),
            visible = nowVisible.drop(dropped.count { it.matches(s.minLevel, s.query) }),
        )
    }

    companion object {
        /** Last 200 lines, then follow. */
        const val SERVICE = "shell:logcat -v threadtime -T 200"
        const val MAX_LINES = 5000
    }
}

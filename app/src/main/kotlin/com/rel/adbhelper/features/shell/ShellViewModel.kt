package com.rel.adbhelper.features.shell

import com.rel.adbhelper.core.adb.AdbClient
import com.rel.adbhelper.core.adb.AdbStream
import com.rel.adbhelper.features.power.isRejection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Newest lines kept in [ShellViewModel.State.output]; older ones are dropped so a chatty
 * command (`logcat`) cannot grow the list, which is copied on every chunk, without bound. */
internal const val MAX_OUTPUT_ENTRIES = 2000

class ShellViewModel(
    private val client: AdbClient,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
    /** Exposed for tests only — see [restart]'s doc on why production never overrides this. */
    private val closeDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    data class State(
        val output: List<String> = emptyList(),
        val connected: Boolean = false,
        val message: String = "",
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    // Written from the UI thread, the open coroutine and the reader (IO); see endSession().
    @Volatile private var stream: AdbStream? = null
    @Volatile private var reader: Job? = null
    @Volatile private var closed = false

    fun start() {
        if (stream != null) return
        closed = false
        scope.launch {
            try {
                val opened = client.openService("shell:")
                // close() ran while the open was still in flight: nothing else will ever close
                // this stream, so do it here.
                if (closed) {
                    withContext(closeDispatcher) { opened.close() }
                    return@launch
                }
                stream = opened
                _state.update { it.copy(connected = true, message = "") }
                reader = scope.launch {
                    // A forced close (see restart's doc) makes a blocked readChunk() throw
                    // IOException("Stream closed.") rather than respond to plain coroutine
                    // cancellation — live-reproduced: restarting the terminal crashed the whole
                    // app with this exact uncaught exception from the OLD reader coroutine,
                    // right after fixing the close() main-thread crash exposed it. Cancellation
                    // (from restart's reader?.cancel()) still needs to propagate, not be
                    // swallowed here.
                    //
                    // The reader works on its own stream [opened], not the `stream` field: after
                    // restart() the field holds the NEW session's stream, and the old reader
                    // finishing late must neither clear it nor post "Shell session ended" over
                    // the new session.
                    try {
                        // A chunk boundary can fall inside a line, so only whole lines become
                        // rows; the unfinished tail waits here for the next chunk (bytes, not
                        // text, so a split multi-byte character survives).
                        val pending = ByteArrayOutputStream()
                        while (true) {
                            val chunk = opened.readChunk() ?: break
                            val lastNewline = chunk.lastIndexOf('\n'.code.toByte())
                            if (lastNewline < 0) {
                                pending.write(chunk)
                                continue
                            }
                            pending.write(chunk, 0, lastNewline)
                            val text = pending.toString(Charsets.UTF_8.name())
                            pending.reset()
                            pending.write(chunk, lastNewline + 1, chunk.size - lastNewline - 1)
                            appendOutput(text.split('\n').map { it.removeSuffix("\r") })
                        }
                        // End of stream: whatever is left is the last (unterminated) line.
                        if (pending.size() > 0) appendOutput(listOf(pending.toString(Charsets.UTF_8.name())))
                        endSession(opened)
                    } catch (c: kotlinx.coroutines.CancellationException) {
                        throw c
                    } catch (t: Throwable) {
                        endSession(opened)
                    }
                }
            } catch (t: Throwable) {
                _state.update { it.copy(connected = false, message = "Could not open shell") }
                stream = null
            }
        }
    }

    private fun appendOutput(lines: List<String>) {
        _state.update { it.copy(output = (it.output + lines).takeLast(MAX_OUTPUT_ENTRIES)) }
    }

    /** Marks the session ended, but only if [ended] is still the current one. */
    private fun endSession(ended: AdbStream) {
        synchronized(this) {
            if (stream !== ended) return
            stream = null
            _state.update { it.copy(connected = false, message = "Shell session ended") }
        }
    }

    fun send(command: String) {
        val s = stream ?: return
        scope.launch {
            // A write on a dead stream throws; uncaught in this handler-less scope it would crash
            // the whole app.
            try {
                s.write((command + "\n").toByteArray(Charsets.UTF_8))
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                _state.update { it.copy(connected = false, message = "Shell session ended") }
            }
        }
    }

    fun clear() {
        _state.update { it.copy(output = emptyList()) }
    }

    /**
     * [AdbStream.close] sends a CLSE packet over the socket — real, blocking network I/O, even
     * though the interface exposes it as a plain non-suspend function (so a watchdog coroutine
     * can call it as ordinary cleanup). Calling it straight from this button's onClick, on the
     * main thread, crashed with `NetworkOnMainThreadException` — live-reproduced. [scope]
     * defaults to [Dispatchers.Unconfined] elsewhere in this class, which runs eagerly on
     * whatever thread launched it rather than hopping off it, so the close still needs an
     * explicit off-main dispatcher here rather than just wrapping it in [CoroutineScope.launch].
     */
    fun restart() {
        scope.launch(closeDispatcher) {
            val old = synchronized(this@ShellViewModel) { stream.also { stream = null } }
            old?.close()
            reader?.cancel()
            reader = null
            _state.update { it.copy(output = emptyList(), connected = false, message = "") }
            start()
        }
    }

    /** Ends the session when the Shell tab is left — otherwise every visit leaks a blocked reader
     * thread and a remote shell. The stream is closed off the calling thread, see [restart]. */
    fun close() {
        closed = true
        val s = stream
        stream = null
        reader?.cancel()
        reader = null
        if (s != null) scope.launch(closeDispatcher) { runCatching { s.close() } }
    }

    /** One-shot `setprop ctl.restart adbd` — needs root on the target; failure surfaces as a
     * message. A restarting adbd drops this very connection before an exit code can come back,
     * so only the target actually rejecting the command counts as failure. */
    fun restartAdbServer() {
        scope.launch {
            _state.update { it.copy(message = "adb server restart requested") }
            if (client.shell("setprop ctl.restart adbd").isRejection()) {
                _state.update { it.copy(message = "Failed to restart adb server (needs root on the target)") }
            }
        }
    }
}

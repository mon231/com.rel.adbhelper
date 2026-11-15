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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Newest chunks kept in [ShellViewModel.State.output]; older ones are dropped so a chatty
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

    private var stream: AdbStream? = null
    private var reader: Job? = null
    private var closed = false

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
                _state.value = _state.value.copy(connected = true, message = "")
                reader = scope.launch {
                    // A forced close (see restart's doc) makes a blocked readChunk() throw
                    // IOException("Stream closed.") rather than respond to plain coroutine
                    // cancellation — live-reproduced: restarting the terminal crashed the whole
                    // app with this exact uncaught exception from the OLD reader coroutine,
                    // right after fixing the close() main-thread crash exposed it. Cancellation
                    // (from restart's reader?.cancel()) still needs to propagate, not be
                    // swallowed here.
                    try {
                        while (true) {
                            val chunk = stream?.readChunk() ?: break
                            _state.value = _state.value.copy(
                                output = (_state.value.output + chunk.toString(Charsets.UTF_8))
                                    .takeLast(MAX_OUTPUT_ENTRIES),
                            )
                        }
                        _state.value = _state.value.copy(connected = false, message = "Shell session ended")
                    } catch (c: kotlinx.coroutines.CancellationException) {
                        throw c
                    } catch (t: Throwable) {
                        _state.value = _state.value.copy(connected = false, message = "Shell session ended")
                    }
                    stream = null
                }
            } catch (t: Throwable) {
                _state.value = _state.value.copy(connected = false, message = "Could not open shell")
                stream = null
            }
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
                _state.value = _state.value.copy(connected = false, message = "Shell session ended")
            }
        }
    }

    fun clear() {
        _state.value = _state.value.copy(output = emptyList())
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
            stream?.close()
            stream = null
            reader?.cancel()
            reader = null
            _state.value = _state.value.copy(output = emptyList(), connected = false, message = "")
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
            _state.value = _state.value.copy(message = "adb server restart requested")
            if (client.shell("setprop ctl.restart adbd").isRejection()) {
                _state.value = _state.value.copy(message = "Failed to restart adb server (needs root on the target)")
            }
        }
    }
}

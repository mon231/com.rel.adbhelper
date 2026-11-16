package com.rel.adbhelper.core.adb

import com.rel.adbhelper.core.shell.shellQuote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Marker appended to every shell command's output so [shell] can recover the remote exit code.
 * Plain `shell:<command>` service strings (what [AdbTransport] implementations expose) return
 * raw decoded stdout with no separate exit-status channel, so the exit code is captured the
 * same way a plain adb shell session would: run the command, then echo `$?`.
 */
private const val EXIT_CODE_MARKER = "__ADBHELPER_EXIT__:"
private val EXIT_CODE_LINE = Regex("$EXIT_CODE_MARKER(\\d+)\\s*\$")

/** Comfortably longer than the whole marker line (marker, exit code, line ending). */
private const val EXIT_MARKER_TAIL_BYTES = 64

/** Every one-shot call below is bounded by this — without it, an unresponsive or unreachable
 * target leaves the calling screen on "Loading…"/stuck forever instead of surfacing a failure.
 * [AdbClient.openService] is deliberately excluded: it hands back a long-lived stream (terminal,
 * mirror) whose whole point is to stay open past any such window.
 *
 * Public so callers that wrap an [AdbClient] call in their own outer timeout can see this value
 * and stay strictly above it — see [timingOutOnClose]'s doc on why an outer timeout shorter than
 * (or equal to) this one silently defeats the watchdog below instead of just adding a second,
 * redundant bound. [AdbClient]'s own `commandTimeoutMillis` constructor param can also be set
 * below this default instead, which keeps the same invariant without needing an outer wrapper at
 * all — prefer that over adding a shorter outer `withTimeout`.
 */
const val COMMAND_TIMEOUT_MILLIS = 15_000L

/**
 * Per-chunk watchdog bound for [AdbClient.raw] specifically — far shorter than
 * [COMMAND_TIMEOUT_MILLIS]. Live-reproduced on the Live Screen tab: the confirmed
 * `libadb-android` CLSE-races-WRTE bug (see [AdbClient.raw]'s doc) doesn't only hit the rare
 * "stream closed on an already-empty queue, throws immediately" case — the far more common case
 * at the natural end of a one-shot binary read is "queue non-empty when CLSE arrives, next read
 * blocks forever" (the other half of the same bug), which needs this watchdog to force it closed
 * before [AdbClient.raw] can treat it as EOF at all. At [COMMAND_TIMEOUT_MILLIS], that meant
 * *every* screenshot capture risked silently paying a full 15-second stall right at its last
 * chunk — measured live, happening roughly once every 10-15 captures, each one looking like the
 * whole feature had frozen. A one-shot binary read has no shell exit code to legitimately wait
 * 15s for; 2s is already generous for a single already-buffered-locally chunk once the rest of a
 * same-sized capture has consistently finished in a second or two.
 */
const val RAW_CHUNK_TIMEOUT_MILLIS = 2_000L

/** How long [AdbClient.push] waits for the target's file size to catch up before calling the
 * write failed — see its doc. */
private const val PUSH_VERIFY_ATTEMPTS = 5
private const val PUSH_VERIFY_RETRY_MILLIS = 200L

/**
 * The library's stream reads/writes are raw blocking socket I/O with no timeout of their own —
 * there is no suspension point inside them for coroutine cancellation to land on, so a plain
 * `withTimeout` wrapped around a call to [AdbStream.readChunk]/[AdbStream.write] cannot actually
 * interrupt it: it marks the coroutine cancelled, but the blocked thread only
 * notices once the blocking call itself returns, which on a dead link is never. Closing the
 * stream from a separate watchdog coroutine is what actually unblocks it — the underlying socket
 * throws on the blocked thread once it's closed out from under it (true on Android/Linux, which
 * is what this ships on). Reproduced live against a real, unresponsive target: `pm list packages`
 * sat on "Loading…" for nearly a minute despite this client's own 15s timeout, before this fix.
 *
 * Deliberately NOT nested inside an outer `withTimeout` of the same duration: a same-duration
 * outer timeout races this watchdog's own `delay`, and if the outer cancellation reaches this
 * coroutine scope first, it cancels the watchdog's `delay` before it ever calls `close()` —
 * cooperative cancellation alone, with nothing left to unstick the real blocked thread. This
 * watchdog has to be the only clock racing the blocking call, not one of two.
 */
private suspend fun <T> AdbStream.timingOutOnClose(
    timeoutMillis: Long,
    watchdogDispatcher: CoroutineDispatcher,
    onTimeout: () -> Unit,
    block: suspend AdbStream.() -> T,
): T = coroutineScope {
    val work = async { block() }
    // watchdogDispatcher, not inherited: when this whole chain is kicked off from a caller
    // sitting on Dispatchers.Unconfined (several ViewModels' default scope), an inherited
    // watchdog's own `delay` never got a dispatcher that would actually resume it — it just
    // never fired, leaving nothing to unstick the blocked read. Reproduced live against a real
    // target: watched the watchdog arm but never fire, even tens of seconds past its timeout,
    // with "Connecting…" stuck on screen the whole time. Production always passes
    // [Dispatchers.Default]; tests pass their own test dispatcher so the watchdog still
    // participates in the test's virtual time instead of racing real wall-clock time.
    val watchdog = launch(watchdogDispatcher) {
        delay(timeoutMillis)
        // A stream forced closed this way means something was genuinely stuck mid-protocol, not
        // just slow — onTimeout flags the shared connection as suspect (see
        // AdbTransport.invalidate's doc) *before* closing, so the next caller reconnects instead
        // of silently reusing a connection this close() may have left broken. Live-reproduced:
        // without this, one timed-out command left every later one-shot call in the same
        // session failing silently against the same now-dead connection.
        onTimeout()
        close()
    }
    try {
        work.await()
    } finally {
        watchdog.cancel()
    }
}

/** One watchdog-bounded read; the library's confirmed benign `IOException("Stream closed.")` is
 * returned as end of stream (null), see [AdbClient.raw]'s doc. */
private suspend fun AdbStream.readChunkOrEof(
    timeoutMillis: Long,
    watchdogDispatcher: CoroutineDispatcher,
    onTimeout: () -> Unit,
): ByteArray? = try {
    timingOutOnClose(timeoutMillis, watchdogDispatcher, onTimeout) { readChunk() }
} catch (e: IOException) {
    if (e.message == "Stream closed.") null else throw e
}

/** android.util.Log, unless this runs on the plain JVM (unit tests), where it is not available. */
private fun androidWarn(message: String) {
    try {
        android.util.Log.w("AdbClient", message)
    } catch (_: RuntimeException) {
    }
}

class AdbClient(
    private val transport: AdbTransport,
    /** Exposed for tests only, to prove the watchdog-close behavior below without a real 15s wait. */
    private val commandTimeoutMillis: Long = COMMAND_TIMEOUT_MILLIS,
    /** Exposed for tests only — see [timingOutOnClose]'s doc on why production never overrides this. */
    private val watchdogDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** Exposed for tests only — see [raw]'s doc on why this is its own, much shorter, value than
     * [commandTimeoutMillis]. */
    private val rawChunkTimeoutMillis: Long = RAW_CHUNK_TIMEOUT_MILLIS,
    /** Receives one line per transport failure; tests pass their own. Never given file contents. */
    private val log: (String) -> Unit = ::androidWarn,
) {

    private fun logFailure(what: String, t: Throwable) = log("$what failed: ${t.javaClass.simpleName}: ${t.message}")

    /** [timeoutMillis] bounds each wait for the command's next output, not the whole command —
     * raise it for a command that legitimately prints nothing for a long time (`pm install` of a
     * large APK), which the default would otherwise force-close mid-run. */
    suspend fun shell(command: String, timeoutMillis: Long = commandTimeoutMillis): AdbResult = try {
        runShell(command, timeoutMillis)
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        logFailure("shell:$command", t)
        AdbResult.TransportUnreachable(t)
    }

    /** Opens a long-lived service stream (e.g. `"shell:"` for a terminal, `"localabstract:<socket>"`
     * for mirroring). The caller owns the stream's lifecycle — not time-bounded, see
     * [COMMAND_TIMEOUT_MILLIS]'s doc. */
    suspend fun openService(service: String): AdbStream = transport.open(service)

    /**
     * Reads a raw service's full byte output — no shell exit-marker framing. Reads chunk-by-chunk
     * rather than in one read-to-EOF call so a confirmed library bug (see [runShell]'s doc) has
     * only two failure modes left to handle here, both live-reproduced capturing screenshots on
     * the Live Screen tab: when the remote CLSE packet races an already-drained read queue,
     * [AdbStream.read] throws `IOException("Stream closed.")` instead of signaling EOF the normal
     * way; when CLSE instead races a still-queued read, the next read blocks forever, needing
     * [RAW_CHUNK_TIMEOUT_MILLIS]'s watchdog to force it closed — which throws that exact same
     * message. Unlike [runShell], this has no embedded completion marker to fall back on (binary
     * data), so that exact, confirmed-benign exception is treated as a normal end of stream
     * instead of a failure, from either path.
     *
     * A caller that knows how its data ends passes [endMarker] (a PNG's IEND trailer): reading
     * stops, and the stream is closed normally, as soon as the data received so far ends with it.
     * That avoids waiting for the watchdog above, which would also [AdbTransport.invalidate] the
     * shared connection and so break every other stream open on it. Only the last
     * `endMarker.size` bytes are compared, across chunk boundaries.
     */
    suspend fun raw(service: String, endMarker: ByteArray? = null): ByteArray = try {
        val stream = openWithTimeout(service)
        try {
            val buffer = java.io.ByteArrayOutputStream()
            var tail = ByteArray(0)
            while (true) {
                val chunk = stream.readChunkOrEof(rawChunkTimeoutMillis, watchdogDispatcher, transport::invalidate) ?: break
                buffer.write(chunk)
                if (endMarker == null) continue
                val recent = chunk.copyOfRange(maxOf(0, chunk.size - endMarker.size), chunk.size)
                tail = (tail + recent).let { it.copyOfRange(maxOf(0, it.size - endMarker.size), it.size) }
                if (tail.contentEquals(endMarker)) break
            }
            buffer.toByteArray()
        } finally {
            stream.close()
        }
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        logFailure(service, t)
        throw IOException("failed to read from $service", t)
    }

    /**
     * Streams [input] onto the target's [path] via `exec:cat >`, one [chunkSize]-sized write at
     * a time — only one chunk is ever held in memory, so a file far larger than the heap can be
     * uploaded. Each write (and the initial open) is individually timeout-bounded, not the whole
     * transfer — a large file legitimately taking minutes isn't penalized the way one
     * whole-operation timeout would, while a connection that goes quiet mid-transfer still gets
     * caught within [COMMAND_TIMEOUT_MILLIS] of its last successful write.
     *
     * [input] is read on [Dispatchers.IO] (it is blocking I/O, e.g. a content resolver stream)
     * and is not closed here; the caller owns it.
     *
     * `cat >` reports nothing back over this stream, so a write the target refused (read-only
     * directory, no space left) looked identical to a successful one — the file's size on the
     * target is checked afterwards against the number of bytes actually sent, and a mismatch
     * thrown. Retried briefly because closing the stream doesn't wait for the remote `cat` to
     * finish flushing what it already received.
     */
    suspend fun push(path: String, input: InputStream, chunkSize: Int = 256 * 1024): Unit = try {
        val stream = openWithTimeout("exec:cat > ${shellQuote(path)}")
        var sent = 0L
        try {
            val buffer = ByteArray(chunkSize)
            while (true) {
                val filled = withContext(Dispatchers.IO) {
                    var n = 0
                    while (n < buffer.size) {
                        val read = input.read(buffer, n, buffer.size - n)
                        if (read < 0) break
                        n += read
                    }
                    n
                }
                if (filled == 0) break
                val chunk = buffer.copyOf(filled)
                stream.timingOutOnClose(commandTimeoutMillis, watchdogDispatcher, transport::invalidate) {
                    write(chunk)
                }
                sent += filled
            }
        } finally {
            stream.close()
        }
        var attempt = 1
        while (true) {
            val remoteSize = shell("stat -c %s ${shellQuote(path)}").outputOrThrow().trim()
            if (remoteSize == sent.toString()) break
            if (attempt++ == PUSH_VERIFY_ATTEMPTS) {
                throw IOException("wrote $sent bytes but the target has $remoteSize")
            }
            delay(PUSH_VERIFY_RETRY_MILLIS)
        }
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        logFailure("push $path", t)
        throw IOException("failed to write to $path", t)
    }

    /** In-memory convenience over the streaming [push], for payloads already in memory. */
    suspend fun push(path: String, data: ByteArray, chunkSize: Int = 256 * 1024) =
        push(path, ByteArrayInputStream(data), chunkSize)

    /**
     * Reads a file off the target via `exec:cat`, one chunk at a time into [sink] — never holds
     * the whole file in memory, and (see [push]'s doc) each chunk is individually
     * timeout-bounded rather than the whole transfer.
     *
     * Like [raw], the library's confirmed end-of-stream bug (`IOException("Stream closed.")`
     * instead of a normal EOF) is treated as the end of the file — which would also make a
     * transfer that was genuinely cut off look complete. So the file's size is read with
     * `stat -c %s` before the first byte is requested, and the number of bytes delivered to
     * [sink] must equal it, else an [IOException] naming both is thrown. Rule: a normal static
     * file; one that grows or shrinks during the transfer fails the check. A `stat` that fails
     * fails the download.
     */
    suspend fun pullTo(path: String, sink: (ByteArray) -> Unit) {
        try {
            val expected = shell("stat -c %s ${shellQuote(path)}").outputOrThrow().trim().toLongOrNull()
                ?: throw IOException("could not read the size of $path")
            val stream = openWithTimeout("exec:cat ${shellQuote(path)}")
            var received = 0L
            try {
                while (true) {
                    val chunk = stream.readChunkOrEof(commandTimeoutMillis, watchdogDispatcher, transport::invalidate) ?: break
                    sink(chunk)
                    received += chunk.size
                }
            } finally {
                stream.close()
            }
            if (received != expected) {
                throw IOException("received $received bytes but the target file has $expected")
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            logFailure("pull $path", t)
            throw IOException("failed to read from $path", t)
        }
    }

    /** Small-payload convenience over [pullTo] (e.g. short config files) — loads the whole
     * result into memory, so callers expecting a possibly-large file should use [pullTo]
     * directly instead (see [com.rel.adbhelper.features.files.FilesViewModel.download]). */
    suspend fun pull(path: String): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        pullTo(path) { buffer.write(it) }
        return buffer.toByteArray()
    }

    /** Bounds [transport.open] — the one place this client still relies on plain coroutine
     * cancellation, because there's no stream yet to close. A target that accepts the raw
     * connection but never completes the handshake can still hang this past [commandTimeoutMillis]
     * (same accepted limitation as [AdbKeyConnectionManager.connectTo]'s doc). */
    private suspend fun openWithTimeout(service: String): AdbStream = try {
        withTimeout(commandTimeoutMillis) { transport.open(service) }
    } catch (c: TimeoutCancellationException) {
        throw IOException("timed out opening $service", c)
    }

    /**
     * Reads chunk-by-chunk, stopping as soon as the embedded exit marker is seen in the
     * accumulated output, rather than waiting for the stream's own EOF. Live-reproduced,
     * decompiled-bytecode-confirmed bug in the underlying ADB protocol library
     * (`io.github.muntashirakon:libadb-android` 3.1.1): when the remote CLSE packet races the
     * last queued WRTE, [AdbStream.read] can block forever instead of ever signaling EOF — the
     * exact "Stream closed" / "timed out opening ..." failures this app kept hitting against
     * real targets. The exit marker this app already appends to every command is a
     * protocol-independent completion signal; trusting it instead of the stream's own (unreliable)
     * end-of-stream notification sidesteps the library bug entirely for every one-shot command.
     */
    private suspend fun runShell(command: String, timeoutMillis: Long): AdbResult {
        val stream = openWithTimeout("shell:$command; echo $EXIT_CODE_MARKER\$?")
        try {
            val buffer = java.io.ByteArrayOutputStream()
            // The marker line only ever sits at the very end of the output, so only the last few
            // bytes received so far are searched per chunk (kept across chunks, since the marker
            // can arrive split in two) — decoding the whole accumulated output after every chunk
            // was quadratic on long outputs. The full text is decoded once, when it's found.
            var tail = ByteArray(0)
            while (true) {
                val chunk = stream.timingOutOnClose(timeoutMillis, watchdogDispatcher, transport::invalidate) {
                    readChunk()
                } ?: return AdbResult.CommandFailed(NO_EXIT_STATUS)
                buffer.write(chunk)
                tail = (tail + chunk.copyOfRange(maxOf(0, chunk.size - EXIT_MARKER_TAIL_BYTES), chunk.size))
                    .let { it.copyOfRange(maxOf(0, it.size - EXIT_MARKER_TAIL_BYTES), it.size) }
                if (!EXIT_CODE_LINE.containsMatchIn(String(tail, Charsets.ISO_8859_1))) continue
                val text = buffer.toString(Charsets.UTF_8.name())
                val match = EXIT_CODE_LINE.find(text) ?: continue
                val exitCode = match.groupValues[1].toInt()
                val output = text.substring(0, match.range.first)
                return if (exitCode == 0) AdbResult.Success(output) else AdbResult.CommandFailed(
                    output.trim().ifEmpty { "command exited with code $exitCode" }
                )
            }
        } finally {
            stream.close()
        }
    }
}

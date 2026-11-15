package com.rel.adbhelper.core.scrcpy

import com.rel.adbhelper.core.adb.AdbClient
import com.rel.adbhelper.core.adb.AdbResult
import com.rel.adbhelper.core.adb.AdbStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.random.Random

/**
 * The connection a [ScrcpySession] runs on, plus what to do when the session is over.
 * Network targets get a dedicated connection (see [ScrcpySession]); [release] disconnects it.
 */
class ScrcpyLink(val client: AdbClient, val release: () -> Unit = {})

/**
 * One scrcpy mirroring session: pushes the bundled server, starts it, and holds the video and
 * control sockets. Needs three simultaneous adb streams (server shell, video, control), which is
 * why the caller picks the [ScrcpyLink]: a dedicated connection on network targets, because
 * concurrent reads on the app's shared library connection are known to misbehave (see
 * `AppsViewModel.load`), and the session client on USB, whose transport multiplexes properly.
 *
 * Order, from the pinned protocol (README.md here): server shell, then the video socket (the
 * server accepts it first and sends a dummy byte), then the control socket, and only then does the
 * server send the device name and codec id on the video socket.
 *
 * [close] must be safe to call from the main thread: the blocking stream closes happen on
 * [closeDispatcher].
 */
class ScrcpySession(
    private val openLink: suspend () -> ScrcpyLink,
    private val loadServerJar: () -> ByteArray,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Exposed for tests only — [AdbStream.close] is blocking network I/O, production uses IO. */
    private val closeDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val scid: Int = Random.nextInt() and 0x7fffffff,
    /** The server needs a moment to start listening; a refused open is retried this many times. */
    private val connectAttempts: Int = 25,
    private val retryDelayMillis: Long = 200,
    private val preambleTimeoutMillis: Long = 10_000,
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val lock = Any()
    private val streams = mutableListOf<AdbStream>()
    private var link: ScrcpyLink? = null
    @Volatile private var closed = false
    private var closeJob: Job? = null

    private val output = StringBuilder()
    @Volatile private var serverEnded = false

    private var videoReader: ByteStreamReader? = null
    private val controlQueue = Channel<ByteArray>(CONTROL_QUEUE_SIZE)

    /** Last text the server printed (stdout and stderr share the shell stream). */
    val serverOutput: String get() = synchronized(output) { output.toString().trim() }

    /**
     * Brings the session up and returns the device name. On failure everything opened so far is
     * closed and an [IOException] carries the server's own output when it has any.
     */
    suspend fun start(): String {
        try {
            val link = openLink()
            synchronized(lock) { if (closed) { link.release(); throw IOException("Session closed") } else this.link = link }
            val client = link.client
            ensureServerJar(client)

            val server = register(client.openService("shell:${ScrcpyProtocol.serverCommand(scid)}"))
            scope.launch { drainServer(server) }

            val video = openSocket(client, readDummyByte = true)
            val reader = ByteStreamReader(video)
            videoReader = reader
            val control = openSocket(client, readDummyByte = false)
            scope.launch { drainControl(control) }
            scope.launch { writeControl(control) }

            val info = withCloseTimeout(video, preambleTimeoutMillis) { ScrcpyProtocol.readStreamInfo(reader) }
            return info.deviceName
        } catch (c: CancellationException) {
            close()
            throw c
        } catch (t: Throwable) {
            val message = failureMessage(t)
            close()
            throw if (t is IOException && t.message == message) t else IOException(message, t)
        }
    }

    /** Next video item. Blocks until one arrives; throws when the stream ends or the session closes. */
    suspend fun readPacket(): ScrcpyProtocol.VideoPacket =
        ScrcpyProtocol.readVideoPacket(checkNotNull(videoReader) { "Session not started" })

    /** Queues a control message. False when the queue is full or the session is closed. */
    fun sendControl(message: ByteArray): Boolean = !closed && controlQueue.trySend(message).isSuccess

    /** The best explanation for [cause]: the server's own output when it printed any. */
    fun failureMessage(cause: Throwable? = null): String {
        val text = serverOutput.lines().filter { it.isNotBlank() }.takeLast(MAX_ERROR_LINES).joinToString("\n")
        if (text.isNotEmpty()) return text
        return cause?.message ?: "The scrcpy server stopped before the video stream opened"
    }

    /** Idempotent. Returns the job that finishes once every stream is closed. */
    fun close(): Job = synchronized(lock) {
        closeJob?.let { return it }
        closed = true
        controlQueue.close()
        val toClose = streams.toList()
        streams.clear()
        val toRelease = link
        link = null
        val job = CoroutineScope(closeDispatcher).launch {
            // Cancelling the readers does not interrupt a blocked read; closing the streams does.
            toClose.forEach { runCatching { it.close() } }
            scope.cancel()
            runCatching { toRelease?.release?.invoke() }
        }
        closeJob = job
        job
    }

    private fun register(stream: AdbStream): AdbStream {
        synchronized(lock) {
            if (!closed) {
                streams.add(stream)
                return stream
            }
        }
        closeAsync(stream)
        throw IOException("Session closed")
    }

    private suspend fun ensureServerJar(client: AdbClient) {
        val jar = loadServerJar()
        val existing = (client.shell("stat -c %s ${ScrcpyProtocol.SERVER_PATH}") as? AdbResult.Success)?.output?.trim()
        if (existing == jar.size.toString()) return
        client.push(ScrcpyProtocol.SERVER_PATH, jar)
    }

    /**
     * Opens the server's abstract socket, retrying while the server starts. A forward tunnel
     * accepts the connection even with nothing listening behind it, so the first socket also reads
     * the dummy byte: that read is what proves the server is really there.
     */
    private suspend fun openSocket(client: AdbClient, readDummyByte: Boolean): AdbStream {
        val service = ScrcpyProtocol.socketService(scid)
        var lastError: Throwable? = null
        repeat(connectAttempts) { attempt ->
            if (attempt > 0) {
                if (serverEnded) throw IOException(failureMessage(lastError))
                delay(retryDelayMillis)
            }
            val stream = try {
                register(client.openService(service))
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                if (closed) throw t
                lastError = t
                return@repeat
            }
            if (!readDummyByte) return stream
            try {
                withCloseTimeout(stream, preambleTimeoutMillis) { ScrcpyProtocol.readDummyByte(ByteStreamReader(stream)) }
                return stream
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                lastError = t
                if (closed) throw t
                unregisterAndClose(stream)
            }
        }
        throw IOException(failureMessage(lastError))
    }

    private fun closeAsync(stream: AdbStream) {
        CoroutineScope(closeDispatcher).launch { runCatching { stream.close() } }
    }

    private fun unregisterAndClose(stream: AdbStream) {
        synchronized(lock) { streams.remove(stream) }
        closeAsync(stream)
    }

    /** Closes [stream] if [block] has not finished in time, which is the only way to end a blocked read. */
    private suspend fun <T> withCloseTimeout(stream: AdbStream, millis: Long, block: suspend () -> T): T =
        coroutineScope {
            val watchdog = launch {
                delay(millis)
                closeAsync(stream)
            }
            try {
                block()
            } finally {
                watchdog.cancel()
            }
        }

    private suspend fun drainServer(server: AdbStream) {
        try {
            while (true) {
                val chunk = server.readChunk() ?: break
                val text = String(chunk, Charsets.UTF_8).replace("\r", "")
                synchronized(output) {
                    output.append(text)
                    if (output.length > MAX_OUTPUT_CHARS) output.delete(0, output.length - MAX_OUTPUT_CHARS)
                }
            }
        } catch (_: IOException) {
            // Closed locally or by the target; either way the server is gone.
        } finally {
            serverEnded = true
        }
    }

    /** The server may send device messages on the control socket; nothing here uses them. */
    private suspend fun drainControl(control: AdbStream) {
        try {
            while (control.readChunk() != null) { /* discard */ }
        } catch (_: IOException) {
        }
    }

    private suspend fun writeControl(control: AdbStream) {
        try {
            for (message in controlQueue) control.write(message)
        } catch (_: IOException) {
            // Surfaces through the video stream ending; sendControl just stops being useful.
        }
    }

    private companion object {
        const val CONTROL_QUEUE_SIZE = 512
        const val MAX_OUTPUT_CHARS = 4000
        const val MAX_ERROR_LINES = 6
    }
}

package com.rel.adbhelper.features.mirror

import com.rel.adbhelper.core.scrcpy.ControlMessages
import com.rel.adbhelper.core.scrcpy.ScrcpyProtocol.VideoPacket
import com.rel.adbhelper.core.scrcpy.ScrcpySession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/** Receives the video stream on the feed thread. Implemented by the MediaCodec wrapper. */
interface VideoSink {
    /** A new capture session of this size starts (first connect, rotation, reset). Config follows. */
    fun onSession(width: Int, height: Int)
    fun onConfig(data: ByteArray)
    fun onMedia(data: ByteArray, ptsMicros: Long, keyFrame: Boolean)
}

class MirrorViewModel(
    private val sessionFactory: () -> ScrcpySession,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
    /** Runs the blocking packet read and decode feed. Null: a dedicated thread owned by this view model. */
    feedDispatcher: CoroutineDispatcher? = null,
    private val startDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    sealed interface State {
        data object Connecting : State
        data class Running(val deviceName: String, val videoSize: VideoSize?) : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Connecting)
    val state: StateFlow<State> = _state.asStateFlow()

    private val ownedExecutor: ExecutorService? =
        if (feedDispatcher == null) Executors.newSingleThreadExecutor { r -> Thread(r, "mirror-feed").apply { isDaemon = true } } else null
    private val feed: CoroutineDispatcher = feedDispatcher ?: ownedExecutor!!.asCoroutineDispatcher()

    private val session = AtomicReference<ScrcpySession?>(null)
    @Volatile private var sink: VideoSink? = null
    @Volatile private var closed = false
    @Volatile private var videoSize: VideoSize? = null
    private var job: Job? = null

    /** Pointer ids currently down, touched from the main thread only. */
    private val activePointers = mutableSetOf<Long>()

    /** Set before [start]; called on the feed thread. */
    fun setSink(sink: VideoSink?) {
        this.sink = sink
    }

    fun start() {
        if (job?.isActive == true || closed) return
        job = scope.launch {
            val s = sessionFactory()
            session.set(s)
            if (closed) {
                session.getAndSet(null)?.close()
                return@launch
            }
            try {
                val name = withContext(startDispatcher) { s.start() }
                _state.update { State.Running(name, null) }
                withContext(feed) { pump(s, name) }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                // Closing out from under a blocked read is the expected way for the pump to end.
                if (!closed) fail(s.failureMessage(t))
            }
        }
    }

    private suspend fun pump(s: ScrcpySession, name: String) {
        while (true) {
            when (val packet = s.readPacket()) {
                is VideoPacket.Session -> {
                    val size = VideoSize(packet.width, packet.height)
                    videoSize = size
                    _state.update { State.Running(name, size) }
                    sink?.onSession(packet.width, packet.height)
                }
                is VideoPacket.Config -> sink?.onConfig(packet.data)
                is VideoPacket.Media -> sink?.onMedia(packet.data, packet.ptsMicros, packet.keyFrame)
            }
        }
    }

    /** Shows [message] and ends the session. */
    fun fail(message: String) {
        if (closed) return
        _state.update { State.Error(message) }
        session.getAndSet(null)?.close()
    }

    /** Asks the server for a fresh config packet and key frame (after the decoder lost its place). */
    fun requestKeyFrame() {
        session.get()?.sendControl(ControlMessages.resetVideo())
    }

    /**
     * One pointer of a touch gesture, in view coordinates. [action] is a [ControlMessages] action.
     * A touch that starts outside the video is ignored; moves and lifts are clamped to its edge.
     */
    fun touch(action: Int, pointerId: Long, viewX: Float, viewY: Float, viewWidth: Int, viewHeight: Int) {
        val size = videoSize ?: return
        // Moves and lifts only count for a pointer whose down was accepted.
        if (action != ControlMessages.ACTION_DOWN && pointerId !in activePointers) return
        val point = mapToVideo(viewX, viewY, viewWidth, viewHeight, size, clamp = action != ControlMessages.ACTION_DOWN) ?: return
        when (action) {
            ControlMessages.ACTION_DOWN -> activePointers.add(pointerId)
            ControlMessages.ACTION_UP -> activePointers.remove(pointerId)
        }
        val pressure = if (action == ControlMessages.ACTION_UP) 0f else 1f
        session.get()?.sendControl(
            ControlMessages.injectTouch(action, pointerId, point.x, point.y, size.width, size.height, pressure),
        )
    }

    fun pressBack() {
        sendPair(ControlMessages.backOrScreenOn(ControlMessages.ACTION_DOWN), ControlMessages.backOrScreenOn(ControlMessages.ACTION_UP))
    }

    fun pressHome() = pressKey(ControlMessages.KEYCODE_HOME)

    fun pressRecents() = pressKey(ControlMessages.KEYCODE_APP_SWITCH)

    private fun pressKey(keycode: Int) = sendPair(
        ControlMessages.injectKeycode(ControlMessages.ACTION_DOWN, keycode),
        ControlMessages.injectKeycode(ControlMessages.ACTION_UP, keycode),
    )

    private fun sendPair(down: ByteArray, up: ByteArray) {
        val s = session.get() ?: return
        s.sendControl(down)
        s.sendControl(up)
    }

    /** Ends the session. Safe to call repeatedly and from the main thread. */
    fun close() {
        closed = true
        job?.cancel()
        job = null
        // Cancellation cannot interrupt a blocked read; closing the session's streams does.
        session.getAndSet(null)?.close()
        ownedExecutor?.shutdown()
    }
}

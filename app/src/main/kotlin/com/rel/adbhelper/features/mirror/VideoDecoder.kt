package com.rel.adbhelper.features.mirror

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import java.nio.ByteBuffer

/**
 * H.264 decoder rendering straight to a [Surface].
 *
 * Calls arrive from two threads: the video feed thread ([VideoSink]) and the main thread
 * ([setSurface], [release]). Everything touching the codec happens under [lock], so a surface that
 * is being destroyed is never in use by the codec once [setSurface] returns.
 *
 * The codec is created only when both a surface and a config packet are known, and is released
 * when either goes away:
 *  - a new capture session (rotation, reset) drops the codec and the config, the next config
 *    packet starts a fresh one at the new size;
 *  - a destroyed surface drops the codec but keeps the config, and [onNeedKeyFrame] asks the server
 *    for a fresh key frame when the surface comes back, because nothing before a key frame decodes.
 */
class VideoDecoder(
    private val onNeedKeyFrame: () -> Unit,
    private val onError: (String) -> Unit,
) : VideoSink {
    private val lock = Any()
    private var surface: Surface? = null
    private var codec: MediaCodec? = null
    private var width = 0
    private var height = 0
    private var config: ByteArray? = null
    private var waitingForKeyFrame = true
    private var consecutiveRestarts = 0
    private var released = false
    private val bufferInfo = MediaCodec.BufferInfo()

    fun setSurface(newSurface: Surface?) = synchronized(lock) {
        if (released || newSurface === surface) return@synchronized
        stopCodec()
        surface = newSurface
        if (newSurface != null && config != null) {
            if (startCodec()) onNeedKeyFrame()
        }
    }

    /** Releases the codec for good; later calls are ignored. */
    fun release() = synchronized(lock) {
        released = true
        stopCodec()
        surface = null
        config = null
    }

    override fun onSession(width: Int, height: Int) = synchronized(lock) {
        if (released) return@synchronized
        stopCodec()
        config = null
        this.width = width
        this.height = height
    }

    override fun onConfig(data: ByteArray) = synchronized(lock) {
        if (released) return@synchronized
        stopCodec()
        config = data
        if (surface != null) startCodec()
    }

    override fun onMedia(data: ByteArray, ptsMicros: Long, keyFrame: Boolean) = synchronized(lock) {
        val c = codec ?: return@synchronized
        if (waitingForKeyFrame) {
            if (!keyFrame) return@synchronized
            waitingForKeyFrame = false
        }
        try {
            val index = c.dequeueInputBuffer(INPUT_TIMEOUT_US)
            if (index < 0) {
                // A frame we could not queue breaks the chain of references: wait for a new key frame.
                waitingForKeyFrame = true
                onNeedKeyFrame()
                return@synchronized
            }
            val buffer: ByteBuffer = c.getInputBuffer(index) ?: return@synchronized
            buffer.clear()
            if (data.size > buffer.capacity()) {
                c.queueInputBuffer(index, 0, 0, ptsMicros, 0)
                waitingForKeyFrame = true
                onNeedKeyFrame()
                return@synchronized
            }
            buffer.put(data)
            c.queueInputBuffer(index, 0, data.size, ptsMicros, 0)
            drainOutput(c)
            consecutiveRestarts = 0
        } catch (e: RuntimeException) {
            restartAfterFailure(e)
        }
    }

    private fun drainOutput(c: MediaCodec) {
        while (true) {
            val out = c.dequeueOutputBuffer(bufferInfo, 0)
            when {
                out >= 0 -> c.releaseOutputBuffer(out, true)
                out == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                else -> Unit // format or buffers changed: nothing to do with a surface output
            }
        }
    }

    private fun startCodec(): Boolean {
        val cfg = config ?: return false
        val target = surface ?: return false
        if (width <= 0 || height <= 0) return false
        var created: MediaCodec? = null
        return try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(cfg))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            created = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            created.configure(format, target, null, 0)
            created.start()
            codec = created
            waitingForKeyFrame = true
            true
        } catch (e: Exception) {
            runCatching { created?.release() }
            codec = null
            onError("Video decoder could not start: ${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    private fun stopCodec() {
        val c = codec ?: return
        codec = null
        runCatching { c.stop() }
        runCatching { c.release() }
    }

    private fun restartAfterFailure(cause: Exception) {
        stopCodec()
        if (++consecutiveRestarts > MAX_RESTARTS) {
            onError("Video decoder failed: ${cause.message ?: cause.javaClass.simpleName}")
            return
        }
        if (surface != null && startCodec()) onNeedKeyFrame()
    }

    private companion object {
        const val INPUT_TIMEOUT_US = 10_000L
        const val MAX_RESTARTS = 3
    }
}

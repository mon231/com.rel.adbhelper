package com.rel.adbhelper.features.mirror

import kotlin.math.max
import kotlin.math.min

data class VideoSize(val width: Int, val height: Int) {
    val aspectRatio: Float get() = width.toFloat() / height.toFloat()
}

data class VideoPoint(val x: Int, val y: Int)

/**
 * Maps a position in a view of [viewWidth] x [viewHeight] pixels to a pixel of the [video].
 *
 * The video is drawn as large as fits while keeping its aspect ratio and is centred, so a view
 * whose shape differs from the video (the moment after a rotation, before the layout catches up)
 * has bars at the sides or top and bottom. A touch in a bar returns null, unless [clamp] is set,
 * which moves it to the nearest video edge. Clamping is for the rest of a gesture that began on
 * the video: a finger sliding off the edge should still end its stroke, not lose it.
 *
 * [video] is always the *current* size, which is swapped (width and height exchanged) by the
 * server when the device rotates; the control message must carry that same size or the server
 * drops the event.
 */
fun mapToVideo(
    viewX: Float,
    viewY: Float,
    viewWidth: Int,
    viewHeight: Int,
    video: VideoSize,
    clamp: Boolean = false,
): VideoPoint? {
    if (viewWidth <= 0 || viewHeight <= 0 || video.width <= 0 || video.height <= 0) return null
    val scale = min(viewWidth.toFloat() / video.width, viewHeight.toFloat() / video.height)
    val shownWidth = video.width * scale
    val shownHeight = video.height * scale
    val left = (viewWidth - shownWidth) / 2f
    val top = (viewHeight - shownHeight) / 2f
    var x = (viewX - left) / scale
    var y = (viewY - top) / scale
    val inside = x >= 0f && y >= 0f && x < video.width && y < video.height
    if (!inside && !clamp) return null
    x = max(0f, min(x, (video.width - 1).toFloat()))
    y = max(0f, min(y, (video.height - 1).toFloat()))
    return VideoPoint(x.toInt(), y.toInt())
}

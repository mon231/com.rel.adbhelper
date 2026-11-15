package com.rel.adbhelper.features.mirror

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.rel.adbhelper.core.scrcpy.ControlMessages
import com.rel.adbhelper.core.scrcpy.createScrcpySession
import com.rel.adbhelper.core.session.DeviceSession

private class MirrorHost(val viewModel: MirrorViewModel, val decoder: VideoDecoder)

@Composable
fun MirrorScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Bumped by "Try again": a failed session is replaced, not revived.
    var attempt by remember { mutableIntStateOf(0) }
    val host = remember(attempt) {
        val client = requireNotNull(DeviceSession.client)
        val target = DeviceSession.target
        lateinit var viewModel: MirrorViewModel
        val decoder = VideoDecoder(
            onNeedKeyFrame = { viewModel.requestKeyFrame() },
            onError = { viewModel.fail(it) },
        )
        viewModel = MirrorViewModel(sessionFactory = { createScrcpySession(context, target, client) })
        viewModel.setSink(decoder)
        MirrorHost(viewModel, decoder)
    }
    val viewModel = host.viewModel
    val state by viewModel.state.collectAsState()

    LaunchedEffect(host) { viewModel.start() }
    // Leaving the tab must stop the server and release the codec, not leave them running unseen.
    DisposableEffect(host) {
        onDispose {
            viewModel.close()
            host.decoder.release()
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            // Always composed, so the surface exists by the time the first frame arrives.
            val size = (state as? MirrorViewModel.State.Running)?.videoSize
            VideoSurface(host, size)
            when (val s = state) {
                is MirrorViewModel.State.Connecting -> Status("Starting screen mirroring…", busy = true)
                is MirrorViewModel.State.Running ->
                    if (s.videoSize == null) Status("Waiting for video from ${s.deviceName}…", busy = true)
                is MirrorViewModel.State.Error -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(16.dp),
                ) {
                    Text(
                        s.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = { attempt++ }) { Text("Try again") }
                }
            }
        }
        val running = state is MirrorViewModel.State.Running
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = viewModel::pressBack, enabled = running, modifier = Modifier.weight(1f)) { Text("Back") }
            OutlinedButton(onClick = viewModel::pressHome, enabled = running, modifier = Modifier.weight(1f)) { Text("Home") }
            OutlinedButton(onClick = viewModel::pressRecents, enabled = running, modifier = Modifier.weight(1f)) { Text("Recents") }
        }
    }
}

@Composable
private fun Status(text: String, busy: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (busy) CircularProgressIndicator()
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * The video. Sized to the video's aspect ratio (a SurfaceView stretches whatever it shows to its
 * bounds), so touches map linearly; [mapToVideo] still handles any momentary mismatch.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
private fun VideoSurface(host: MirrorHost, size: VideoSize?) {
    val ratio = size?.aspectRatio ?: (9f / 16f)
    AndroidView(
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = host.decoder.setSurface(holder.surface)
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                    // Must not return while the codec still renders to the surface: setSurface(null)
                    // releases it under the decoder's lock.
                    override fun surfaceDestroyed(holder: SurfaceHolder) = host.decoder.setSurface(null)
                })
                setOnTouchListener { v, event ->
                    forwardTouch(host.viewModel, event, v.width, v.height)
                    true
                }
            }
        },
        // Fit inside the available space at the video's aspect ratio.
        modifier = Modifier.layout { measurable, constraints ->
            val maxW = constraints.maxWidth
            val maxH = constraints.maxHeight
            var w = maxW
            var h = (w / ratio).toInt()
            if (h > maxH) {
                h = maxH
                w = (h * ratio).toInt()
            }
            val placeable = measurable.measure(
                androidx.compose.ui.unit.Constraints.fixed(w.coerceAtLeast(1), h.coerceAtLeast(1)),
            )
            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        },
    )
}

/** One [ControlMessages] touch message per affected pointer, keyed by the pointer's id. */
private fun forwardTouch(viewModel: MirrorViewModel, event: MotionEvent, width: Int, height: Int) {
    fun send(action: Int, index: Int) = viewModel.touch(
        action, event.getPointerId(index).toLong(), event.getX(index), event.getY(index), width, height,
    )
    when (event.actionMasked) {
        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> send(ControlMessages.ACTION_DOWN, event.actionIndex)
        MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> send(ControlMessages.ACTION_UP, event.actionIndex)
        MotionEvent.ACTION_MOVE -> for (i in 0 until event.pointerCount) send(ControlMessages.ACTION_MOVE, i)
        MotionEvent.ACTION_CANCEL -> for (i in 0 until event.pointerCount) send(ControlMessages.ACTION_UP, i)
    }
}

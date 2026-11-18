package com.rel.adbhelper.features.livescreen

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.rel.adbhelper.core.session.DeviceSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun LiveScreenScreen(modifier: Modifier = Modifier) {
    val viewModel = remember { LiveScreenViewModel(requireNotNull(DeviceSession.client)) }
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) { viewModel.start() }
    DisposableEffect(Unit) { onDispose { viewModel.stop() } }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // Decoded here, not in the ViewModel, so the ViewModel stays plain-JVM testable —
        // android.graphics.BitmapFactory has no fake/unit-test substitute in this project.
        // Off the main thread: a full-screen PNG takes long enough to drop frames in composition.
        // The previous bitmap stays on screen until the next one is ready.
        val frame = state.frame
        val bitmap by produceState<ImageBitmap?>(initialValue = null, frame) {
            value = frame?.let {
                withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
            }
        }
        val shown = bitmap
        if (shown != null) {
            Image(bitmap = shown, contentDescription = "Live device screen")
        } else if (state.error.isEmpty()) {
            Text("Connecting…")
        }
        if (state.error.isNotEmpty()) {
            Text(
                state.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

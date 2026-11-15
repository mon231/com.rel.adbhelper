package com.rel.adbhelper.features.logs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.rel.adbhelper.core.logs.LogLine
import com.rel.adbhelper.core.logs.RAW_LEVEL
import com.rel.adbhelper.core.session.DeviceSession
import kotlinx.coroutines.flow.drop

private val LEVELS = listOf('V' to "Verbose", 'D' to "Debug", 'I' to "Info", 'W' to "Warning", 'E' to "Error")

@Composable
fun LogsScreen(modifier: Modifier = Modifier) {
    val viewModel = remember { LogsViewModel(requireNotNull(DeviceSession.client)) }
    val state by viewModel.state.collectAsState()
    // Held locally as well: a text field driven only by the collected flow lags a frame behind
    // the keyboard.
    var query by remember { mutableStateOf("") }
    var levelMenuOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    // Follow the newest line until the user scrolls away from the bottom; scrolling back down
    // to the bottom follows again. Re-evaluated only when a scroll ends, because new lines
    // arriving would otherwise make "at the bottom" false before it could be acted on.
    var follow by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) { viewModel.start() }
    // Leaving the tab must end the remote logcat, not leave it streaming into nothing.
    DisposableEffect(Unit) { onDispose { viewModel.close() } }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .drop(1)
            .collect { scrolling -> if (!scrolling) follow = !listState.canScrollForward }
    }
    LaunchedEffect(state.visible, follow) {
        if (follow && state.visible.isNotEmpty()) listState.scrollToItem(state.visible.size - 1)
    }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { if (state.paused) viewModel.resume() else viewModel.pause() }) {
                Text(if (state.paused) "Resume" else "Pause")
            }
            TextButton(onClick = { viewModel.clear() }) { Text("Clear") }
            Box {
                TextButton(onClick = { levelMenuOpen = true }) { Text("Level: ${state.minLevel}") }
                DropdownMenu(expanded = levelMenuOpen, onDismissRequest = { levelMenuOpen = false }) {
                    LEVELS.forEach { (level, name) ->
                        DropdownMenuItem(
                            text = { Text("$level  $name") },
                            onClick = { levelMenuOpen = false; viewModel.setMinLevel(level) },
                        )
                    }
                }
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                viewModel.setQuery(it)
            },
            placeholder = { Text("filter tag or message") },
            // Log text is device data: monospace.
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        state.message.takeIf { it.isNotEmpty() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).weight(1f),
        ) {
            items(state.visible.size) { index ->
                val line = state.visible[index]
                // Long lines wrap rather than clip.
                Text(
                    line.display(),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = when (line.level) {
                        'E', 'F' -> MaterialTheme.colorScheme.error
                        'W' -> MaterialTheme.colorScheme.tertiary
                        else -> Color.Unspecified
                    },
                )
            }
        }
    }
}

/** Time of day only (the date is dropped to save width), then level, tag and message. */
private fun LogLine.display(): String =
    if (level == RAW_LEVEL) message else "${time.substringAfter(' ')} $level $tag: $message"

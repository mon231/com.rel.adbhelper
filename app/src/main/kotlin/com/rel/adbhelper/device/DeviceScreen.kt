package com.rel.adbhelper.device

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rel.adbhelper.core.session.DeviceSession
import com.rel.adbhelper.features.apps.AppsScreen
import com.rel.adbhelper.features.deviceinfo.InfoScreen
import com.rel.adbhelper.features.files.FilesScreen
import com.rel.adbhelper.features.livescreen.LiveScreenScreen
import com.rel.adbhelper.features.logs.LogsScreen
import com.rel.adbhelper.features.mirror.MirrorScreen
import com.rel.adbhelper.features.power.PowerScreen
import com.rel.adbhelper.features.processes.ProcessListScreen
import com.rel.adbhelper.features.shell.ShellScreen

/** Title and content together, so the tab row and what it shows cannot drift apart. */
private val TABS: List<Pair<String, @Composable () -> Unit>> = listOf(
    "Info" to { InfoScreen() },
    "Apps" to { AppsScreen() },
    "Files" to { FilesScreen() },
    "Shell" to { ShellScreen() },
    "Processes" to { ProcessListScreen() },
    "Logs" to { LogsScreen() },
    "Power" to { PowerScreen() },
    "Monitor" to { LiveScreenScreen() },
    "Mirror" to { MirrorScreen() },
)

/** Hub screen shown once a target is connected; one tab per feature.
 *
 * [ScrollableTabRow], not the fixed [androidx.compose.material3.TabRow]: a fixed tab row divides
 * the screen width evenly across all tabs, and nine labels ("Monitor", "Mirror", …) don't fit
 * un-clipped at that width on a phone-width screen — this scrolls instead of squeezing/clipping
 * labels.
 */
@Composable
fun DeviceScreen(modifier: Modifier = Modifier) {
    var selected by rememberSaveable { mutableIntStateOf(0) }

    // Not on a configuration change (rotation, dark mode): the hub is recomposed straight away
    // and every tab needs the session still attached.
    val activity = LocalActivity.current
    DisposableEffect(Unit) {
        onDispose { if (activity?.isChangingConfigurations != true) DeviceSession.detach() }
    }

    Column(modifier = modifier.fillMaxSize()) {
        ScrollableTabRow(selectedTabIndex = selected, edgePadding = 16.dp) {
            TABS.forEachIndexed { index, (title, _) ->
                Tab(
                    selected = selected == index,
                    onClick = { selected = index },
                    text = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        }
        // key: each tab gets its own composition, so one tab's remembered state is never reused
        // by another and leaving a tab disposes it.
        key(selected) { TABS[selected].second() }
    }
}

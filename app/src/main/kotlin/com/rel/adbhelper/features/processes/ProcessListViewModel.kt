package com.rel.adbhelper.features.processes

import com.rel.adbhelper.core.adb.AdbClient
import com.rel.adbhelper.core.adb.describe
import com.rel.adbhelper.core.adb.outputOrThrow
import com.rel.adbhelper.core.proc.ProcessEntry
import com.rel.adbhelper.core.proc.parsePs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ProcessListViewModel(
    private val client: AdbClient,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
) {
    data class State(
        val loading: Boolean = true,
        val processes: List<ProcessEntry> = emptyList(),
        val message: String = "",
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun load() {
        scope.launch {
            _state.value = State(loading = true)
            _state.value = try {
                State(loading = false, processes = parsePs(client.shell("ps -A").outputOrThrow()))
            } catch (t: Throwable) {
                State(loading = false, message = t.describe())
            }
        }
    }

    fun kill(entry: ProcessEntry) {
        scope.launch {
            val result = client.shell("kill ${entry.pid}")
            _state.update { it.copy(
                message = if (result is com.rel.adbhelper.core.adb.AdbResult.Success) {
                    "Killed ${entry.name}"
                } else {
                    "Failed to kill process"
                },
            ) }
            load()
        }
    }
}

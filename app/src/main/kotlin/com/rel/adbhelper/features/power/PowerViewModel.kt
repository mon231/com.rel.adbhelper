package com.rel.adbhelper.features.power

import com.rel.adbhelper.core.adb.AdbClient
import com.rel.adbhelper.core.adb.AdbResult
import com.rel.adbhelper.core.adb.isRejection
import com.rel.adbhelper.core.adb.describe
import com.rel.adbhelper.core.adb.outputOrThrow
import com.rel.adbhelper.core.power.PowerCommands
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal const val MIN_DENSITY = 72
internal const val MAX_DENSITY = 1000

class PowerViewModel(
    private val client: AdbClient,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
) {
    data class State(
        val currentDensity: Int? = null,
        val densityInput: String = "",
        val message: String = "",
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun load() {
        scope.launch {
            try {
                val wm = client.shell(PowerCommands.currentDensity()).outputOrThrow()
                val density = Regex("Physical density: (\\d+)").find(wm)?.groupValues?.get(1)?.toIntOrNull()
                _state.value = State(currentDensity = density, densityInput = density?.toString().orEmpty())
            } catch (t: Throwable) {
                _state.value = State(message = t.describe())
            }
        }
    }

    fun setDensityInput(text: String) {
        _state.update { it.copy(densityInput = text) }
    }

    fun applyDensity() {
        val dpi = _state.value.densityInput.toIntOrNull() ?: return
        // Far-out values (`wm density 5`) leave a target that cannot be operated by touch.
        if (dpi !in MIN_DENSITY..MAX_DENSITY) {
            _state.update { it.copy(message = "Density must be between $MIN_DENSITY and $MAX_DENSITY") }
            return
        }
        scope.launch {
            val result = client.shell(PowerCommands.setDpi(dpi))
            _state.update { it.copy(
                message = if (result is AdbResult.Success) "Density set to $dpi" else "Failed to change DPI",
                currentDensity = if (result is AdbResult.Success) dpi else it.currentDensity,
            ) }
        }
    }

    fun resetDensity() {
        scope.launch {
            val result = client.shell(PowerCommands.resetDpi())
            _state.update { it.copy(
                message = if (result is AdbResult.Success) "Density reset" else "Failed to change DPI",
            ) }
            load()
        }
    }

    /**
     * [cutsLink]: the command takes the connection down before its exit code can come back
     * (reboot, turning wireless debugging off), so the success message is shown as soon as it is
     * sent and a connection lost afterwards is the expected outcome, not a failure.
     */
    private fun run(command: String, successMessage: String, cutsLink: Boolean = false, onDone: () -> Unit = {}) {
        scope.launch {
            if (cutsLink) _state.update { it.copy(message = successMessage) }
            val result = client.shell(command)
            val succeeded = if (cutsLink) !result.isRejection() else result is AdbResult.Success
            _state.update { it.copy(message = if (succeeded) successMessage else "Command failed") }
            onDone()
        }
    }

    fun reboot() = run(PowerCommands.reboot(), "Rebooting", cutsLink = true)
    fun rebootRecovery() = run(PowerCommands.rebootRecovery(), "Rebooting to recovery", cutsLink = true)
    fun rebootBootloader() = run(PowerCommands.rebootBootloader(), "Rebooting to bootloader", cutsLink = true)
    fun enableWirelessDebugging() = run(PowerCommands.enableWirelessDebugging(), "Wireless debugging enabled")
    fun disableWirelessDebugging() =
        run(PowerCommands.disableWirelessDebugging(), "Wireless debugging disabled", cutsLink = true)
}

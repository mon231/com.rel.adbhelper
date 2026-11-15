package com.rel.adbhelper.features.deviceinfo

import android.content.Context
import com.rel.adbhelper.core.adb.AdbClient
import com.rel.adbhelper.core.adb.AdbResult
import com.rel.adbhelper.core.adb.outputOrThrow
import com.rel.adbhelper.core.device.DeviceInfo
import com.rel.adbhelper.core.device.buildDeviceInfo
import com.rel.adbhelper.core.device.parseGetprop
import com.rel.adbhelper.core.image.isCompletePng
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DeviceInfoViewModel(
    private val client: AdbClient,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
) {
    data class State(
        val loading: Boolean = true,
        val info: DeviceInfo? = null,
        val message: String = "",
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun load() {
        scope.launch {
            _state.value = State(loading = true)
            _state.value = try {
                val props = parseGetprop(client.shell("getprop").outputOrEmpty())
                val battery = client.shell("dumpsys battery").outputOrEmpty()
                val df = client.shell("df -k /data").outputOrEmpty()
                val wm = client.shell("wm size; wm density").outputOrEmpty()
                val ip = client.shell("ip addr show wlan0").outputOrEmpty()
                val cores = client.shell("nproc").outputOrEmpty().trim().toIntOrNull() ?: 0
                State(loading = false, info = buildDeviceInfo(props, battery, df, wm, ip, cores))
            } catch (t: Throwable) {
                State(loading = false, message = "Connection to the device was lost")
            }
        }
    }

    fun screenshot(context: Context) {
        scope.launch {
            _state.update { it.copy(message = "Capturing screenshot…") }
            try {
                val bytes = client.raw("exec:screencap -p")
                if (!isCompletePng(bytes)) throw java.io.IOException("incomplete screenshot")
                val dir = context.getExternalFilesDir("Screenshots") ?: context.filesDir
                val file = java.io.File(dir, "adbhelper-${System.currentTimeMillis()}.png")
                file.writeBytes(bytes)
                _state.update { it.copy(message = "Screenshot saved to ${file.absolutePath}") }
            } catch (t: Throwable) {
                _state.update { it.copy(message = "Failed to capture screenshot") }
            }
        }
    }

    /** One command the target rejects (no wlan0, no `nproc`) only blanks its own row; a dead
     * link throws, so [load] reports it instead of showing an all-empty panel. */
    private fun AdbResult.outputOrEmpty(): String =
        if (this is AdbResult.CommandFailed) "" else outputOrThrow()
}

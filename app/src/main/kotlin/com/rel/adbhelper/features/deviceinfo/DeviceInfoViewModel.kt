package com.rel.adbhelper.features.deviceinfo

import android.content.Context
import com.rel.adbhelper.core.adb.AdbClient
import com.rel.adbhelper.core.adb.AdbResult
import com.rel.adbhelper.core.adb.outputOrThrow
import com.rel.adbhelper.core.device.DeviceInfo
import com.rel.adbhelper.core.device.buildDeviceInfo
import com.rel.adbhelper.core.device.parseGetprop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
private val PNG_IEND = byteArrayOf(0, 0, 0, 0, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte())

/**
 * Whether [bytes] is a whole PNG: the 8-byte signature up front and the IEND chunk at the very
 * end. [AdbClient.raw] treats a stalled read as a normal end of data, so a capture can come back
 * cut short without any error — this is the only way to tell.
 */
internal fun isCompletePng(bytes: ByteArray): Boolean =
    bytes.size >= PNG_SIGNATURE.size + PNG_IEND.size &&
        bytes.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE) &&
        bytes.copyOfRange(bytes.size - PNG_IEND.size, bytes.size).contentEquals(PNG_IEND)

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

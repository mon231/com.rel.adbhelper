package com.rel.adbhelper.connect

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.rel.adbhelper.core.adb.AdbClient
import com.rel.adbhelper.core.adb.AdbKeyConnectionManager
import com.rel.adbhelper.core.adb.AdbResult
import com.rel.adbhelper.core.adb.usb.UsbAdbTransport
import com.rel.adbhelper.core.adb.usb.UsbPacketChannel
import com.rel.adbhelper.core.adb.usb.findAdbInterface
import com.rel.adbhelper.core.session.DeviceSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** App-private action of the broadcast that answers the system's USB permission dialog. */
private const val ACTION_USB_PERMISSION = "com.rel.adbhelper.USB_PERMISSION"

private sealed interface UsbPhase {
    data object Choosing : UsbPhase
    data object AskingPermission : UsbPhase
    data object Connecting : UsbPhase
    data object AwaitingAuthorisation : UsbPhase
    data class Failed(val message: String) : UsbPhase
}

/** The transport of the USB session the app currently holds. Nothing else owns it once the
 * Connect screen has handed the client to [DeviceSession], so the next connect closes it. */
private object UsbSessions {
    private var current: UsbAdbTransport? = null

    @Synchronized
    fun replace(transport: UsbAdbTransport?) {
        current?.close()
        current = transport
    }
}

private fun UsbDevice.label(): String = productName?.takeIf { it.isNotBlank() } ?: deviceName

/**
 * Lets the user pick a USB device with an ADB interface, asks for USB permission, connects and
 * attaches the resulting client to [DeviceSession]. [onConnected] is called on success;
 * [onDismiss] when the dialog is closed (Cancel ends whatever is in progress, including a wait
 * for the user to tap Allow on the target).
 */
@Composable
fun UsbConnectDialog(onConnected: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val flow = remember { UsbConnectFlow(context.applicationContext, scope, onConnected) }
    DisposableEffect(flow) { onDispose { flow.release() } }

    val cancel = {
        flow.cancel()
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = cancel,
        title = { Text("USB-OTG") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (val phase = flow.phase) {
                    UsbPhase.Choosing, is UsbPhase.Failed -> {
                        if (phase is UsbPhase.Failed) {
                            Text(phase.message, color = MaterialTheme.colorScheme.tertiary)
                        }
                        if (flow.devices.isEmpty()) {
                            Text(
                                "No USB device with debugging enabled found. Connect the target with " +
                                    "an OTG cable and enable USB debugging.",
                            )
                        } else {
                            flow.devices.forEach { device ->
                                TextButton(onClick = { flow.select(device) }) { Text(device.label()) }
                            }
                        }
                    }
                    UsbPhase.AskingPermission -> Progress("Waiting for USB permission…")
                    UsbPhase.Connecting -> Progress("Connecting…")
                    UsbPhase.AwaitingAuthorisation -> Progress("Waiting for authorisation on the device…")
                }
            }
        },
        confirmButton = {
            val phase = flow.phase
            if (phase == UsbPhase.Choosing || phase is UsbPhase.Failed) {
                TextButton(onClick = { flow.refresh() }) { Text("Refresh") }
            }
        },
        dismissButton = { TextButton(onClick = cancel) { Text("Cancel") } },
    )
}

@Composable
private fun Progress(text: String) {
    CircularProgressIndicator()
    Text(text)
}

/** State and actions of the dialog; plain class so nothing outlives the composition by accident
 * (see [release]). All members run on the main thread; blocking work runs in [connect]'s
 * coroutine on other dispatchers. */
private class UsbConnectFlow(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onConnected: () -> Unit,
) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    var phase by mutableStateOf<UsbPhase>(UsbPhase.Choosing)
        private set
    var devices by mutableStateOf(listAdbDevices())
        private set

    private var receiver: BroadcastReceiver? = null
    private var job: Job? = null
    private var transport: UsbAdbTransport? = null
    private var handedOver = false

    fun refresh() {
        devices = listAdbDevices()
    }

    fun select(device: UsbDevice) {
        if (phase != UsbPhase.Choosing && phase !is UsbPhase.Failed) return
        if (usbManager.hasPermission(device)) {
            connect(device)
            return
        }
        phase = UsbPhase.AskingPermission
        val answer = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                unregister()
                val answered = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (granted && (answered == null || answered.deviceName == device.deviceName)) {
                    connect(device)
                } else {
                    phase = UsbPhase.Failed("USB permission was denied.")
                }
            }
        }
        receiver = answer
        ContextCompat.registerReceiver(
            context, answer, IntentFilter(ACTION_USB_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        try {
            // The system adds the device and the answer to this intent, so the PendingIntent has
            // to be mutable; Android 14+ only accepts that for an explicit intent, hence the package.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val request = PendingIntent.getBroadcast(
                context, 0, Intent(ACTION_USB_PERMISSION).setPackage(context.packageName), flags,
            )
            usbManager.requestPermission(device, request)
        } catch (e: RuntimeException) {
            unregister()
            phase = UsbPhase.Failed(e.message ?: "Could not ask for USB permission.")
        }
    }

    /** Ends whatever is in progress and closes the channel, which also ends a handshake that is
     * waiting for the user's Allow. A session already handed to [DeviceSession] is left alone. */
    fun cancel() {
        unregister()
        job?.cancel()
        job = null
        if (!handedOver) transport?.close()
        transport = null
    }

    /** The composition is gone. */
    fun release() = cancel()

    private fun connect(device: UsbDevice) {
        phase = UsbPhase.Connecting
        UsbSessions.replace(null)
        job = scope.launch {
            var attempt: UsbAdbTransport? = null
            try {
                val identity = withContext(Dispatchers.IO) {
                    AdbKeyConnectionManager.getInstance(context).usbIdentity()
                }
                attempt = UsbAdbTransport(
                    openChannel = { UsbPacketChannel.open(usbManager, device) },
                    identity = identity,
                    onAwaitingAuthorisation = {
                        scope.launch(Dispatchers.Main) {
                            if (phase == UsbPhase.Connecting) phase = UsbPhase.AwaitingAuthorisation
                        }
                    },
                )
                transport = attempt
                // No time limit here: the person may take a while to tap Allow on the target.
                attempt.connect()
                val client = AdbClient(attempt)
                when (val result = client.shell("echo connected")) {
                    is AdbResult.Success -> Unit
                    is AdbResult.CommandFailed -> throw IOException(result.message)
                    is AdbResult.TransportUnreachable ->
                        throw IOException(result.cause.message ?: "The device did not answer.", result.cause)
                }
                handedOver = true
                UsbSessions.replace(attempt)
                DeviceSession.attach("usb:${device.label()}", client) { UsbSessions.replace(null) }
                onConnected()
            } catch (e: CancellationException) {
                if (!handedOver) attempt?.close()
                throw e
            } catch (e: Throwable) {
                if (!handedOver) attempt?.close()
                transport = null
                phase = UsbPhase.Failed(e.message ?: "Connection failed.")
            }
        }
    }

    private fun unregister() {
        val registered = receiver ?: return
        receiver = null
        runCatching { context.unregisterReceiver(registered) }
    }

    private fun listAdbDevices(): List<UsbDevice> =
        usbManager.deviceList.values.filter { findAdbInterface(it) != null }.sortedBy { it.deviceName }
}

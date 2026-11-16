package com.rel.adbhelper.core.scrcpy

import android.content.Context
import com.rel.adbhelper.connect.parseHostPort
import com.rel.adbhelper.core.adb.AdbClient
import com.rel.adbhelper.core.adb.AdbKeyConnectionManager
import com.rel.adbhelper.core.adb.NetworkAdbTransport

/** The address to dial for a plain network target, or null for one that has its own multiplexing
 * transport (`usb:…`, and the debug build's `raw:host:port`, which would otherwise parse as
 * `host:port` too). */
internal fun networkHostPort(target: String?): Pair<String, Int>? =
    target?.takeUnless { it.startsWith("usb:") || it.startsWith("raw:") }?.let(::parseHostPort)

/**
 * Builds the production [ScrcpySession] for the attached device.
 *
 * Network targets (`DeviceSession.target` parses as `host:port`) get their own connection, because
 * the session holds three streams open at once and the app's shared library connection is known to
 * misbehave under concurrent reads. Anything else (USB) uses [sessionClient], whose transport
 * multiplexes streams properly.
 */
fun createScrcpySession(context: Context, target: String?, sessionClient: AdbClient): ScrcpySession {
    val app = context.applicationContext
    val hostPort = networkHostPort(target)
    return ScrcpySession(
        openLink = {
            if (hostPort == null) {
                ScrcpyLink(sessionClient)
            } else {
                val manager = AdbKeyConnectionManager.createDisposable(app)
                val transport = NetworkAdbTransport(app, hostPort.first, hostPort.second, manager)
                ScrcpyLink(AdbClient(transport)) { runCatching { manager.disconnect() } }
            }
        },
        loadServerJar = { app.assets.open(ScrcpyProtocol.ASSET_NAME).use { it.readBytes() } },
    )
}

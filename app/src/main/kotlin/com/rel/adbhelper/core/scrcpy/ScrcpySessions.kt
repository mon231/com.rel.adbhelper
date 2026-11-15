package com.rel.adbhelper.core.scrcpy

import android.content.Context
import com.rel.adbhelper.connect.parseHostPort
import com.rel.adbhelper.core.adb.AdbClient
import com.rel.adbhelper.core.adb.AdbKeyConnectionManager
import com.rel.adbhelper.core.adb.NetworkAdbTransport

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
    val hostPort = target?.let(::parseHostPort)
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

package com.rel.adbhelper.connect

import android.content.Context
import android.net.wifi.WifiManager
import com.rel.adbhelper.core.net.LanScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface

/** Local device's IPv4 address for the LAN sweep, or null when no network is up. */
suspend fun localSubnet(context: Context): String? = withContext(Dispatchers.IO) {
    val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    val dhcp = wifi?.dhcpInfo
    if (dhcp != null && dhcp.ipAddress != 0) return@withContext intToIp(dhcp.ipAddress)
    NetworkInterface.getNetworkInterfaces().asSequence()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.asSequence() }
        .filterIsInstance<Inet4Address>()
        .filter { it.isSiteLocalAddress }
        .mapNotNull { it.hostAddress }
        .firstOrNull()
}

private fun intToIp(value: Int): String =
    listOf(value and 0xFF, value shr 8 and 0xFF, value shr 16 and 0xFF, value shr 24 and 0xFF).joinToString(".")

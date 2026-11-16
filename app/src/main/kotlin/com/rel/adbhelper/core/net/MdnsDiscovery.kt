package com.rel.adbhelper.core.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.net.Inet4Address
import java.util.ArrayDeque

/** mDNS service types Android's wireless debugging advertises. */
object MdnsServiceTypes {
    const val CONNECT = "_adb-tls-connect._tcp"
    const val PAIRING = "_adb-tls-pairing._tcp"
    const val ADB = "_adb._tcp"
}

/** Discovery of `host:port` services on the LAN. Callbacks arrive on a non-main thread. */
interface MdnsDiscovery {
    /** Starts discovering [serviceType]; every resolved IPv4 service is reported as "host:port". */
    fun start(serviceType: String, onFound: (target: String) -> Unit)

    /** Stops discovery. Safe to call more than once or without [start]. */
    fun stop()
}

/**
 * [MdnsDiscovery] on [NsdManager]. `libadb-android`'s own `AdbMdns` is not used: it only reports
 * services whose host is one of THIS device's interface addresses (it exists to find the phone's
 * own wireless-debugging port), so it never sees another device. Needs only the INTERNET and
 * ACCESS_WIFI_STATE permissions the app already declares.
 */
class NsdMdnsDiscovery(context: Context) : MdnsDiscovery {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val lock = Any()
    private var listener: NsdManager.DiscoveryListener? = null
    private var stopped = false
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    override fun start(serviceType: String, onFound: (String) -> Unit) {
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                synchronized(lock) { pending.add(serviceInfo) }
                resolveNext(onFound)
            }
        }
        synchronized(lock) {
            if (listener != null) return
            stopped = false
            listener = discovery
        }
        runCatching { nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discovery) }
    }

    /** Older Android allows one resolve at a time (FAILURE_ALREADY_ACTIVE), so resolve serially. */
    @Suppress("DEPRECATION")
    private fun resolveNext(onFound: (String) -> Unit) {
        val next = synchronized(lock) {
            if (stopped || resolving) return
            pending.poll()?.also { resolving = true } ?: return
        }
        val done = {
            synchronized(lock) { resolving = false }
            resolveNext(onFound)
        }
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = done()
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = serviceInfo.host as? Inet4Address
                if (host?.hostAddress != null && !synchronized(lock) { stopped }) {
                    onFound(LanScanner.formatTarget(host.hostAddress!!, serviceInfo.port))
                }
                done()
            }
        }
        if (runCatching { nsd.resolveService(next, resolveListener) }.isFailure) done()
    }

    override fun stop() {
        val active = synchronized(lock) {
            stopped = true
            pending.clear()
            listener.also { listener = null }
        } ?: return
        runCatching { nsd.stopServiceDiscovery(active) }
    }
}

/** Adds [found] targets to [existing], de-duplicated and sorted, leaving out any `host:port` whose
 * host is one of [ownAddresses] — this device's own ADB services are announced over mDNS too. */
fun mergeScanResults(
    existing: List<String>,
    found: Collection<String>,
    ownAddresses: Set<String> = emptySet(),
): List<String> =
    (existing + found).filter { it.substringBeforeLast(':') !in ownAddresses }.distinct().sorted()

/** The pairing address to pre-fill: the only discovered service, and only while the field is blank. */
fun pairingPrefill(found: List<String>, current: String): String? =
    if (current.isBlank() && found.size == 1) found.single() else null

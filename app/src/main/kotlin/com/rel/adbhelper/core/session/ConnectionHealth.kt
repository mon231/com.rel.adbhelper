package com.rel.adbhelper.core.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Whether the link looks lost, derived only from results callers already get — it never sends
 * traffic of its own. [AdbClient][com.rel.adbhelper.core.adb.AdbClient] reports every command
 * and stream outcome here; the hub shows a banner while [lost] is non-null. */
class ConnectionHealth {
    private val _lost = MutableStateFlow<Throwable?>(null)

    /** Why the transport dropped (latest failure), or null while the link looks fine. */
    val lost: StateFlow<Throwable?> = _lost

    fun reportFailure(cause: Throwable) { _lost.value = cause }

    /** Any later success proves the link is back. */
    fun reportSuccess() { _lost.value = null }
}

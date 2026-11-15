package com.rel.adbhelper.features.deviceinfo

import com.rel.adbhelper.core.adb.AdbClient
import com.rel.adbhelper.core.adb.FakeAdbTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** [Dispatchers.Unconfined] — see [com.rel.adbhelper.core.adb.AdbClientTest]'s identical
 * `client()` helper's doc. */
private fun client(transport: com.rel.adbhelper.core.adb.AdbTransport) =
    AdbClient(transport, watchdogDispatcher = Dispatchers.Unconfined)

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceInfoViewModelTest {

    @Test
    fun `load reports a lost connection instead of an empty panel`() = runTest {
        val vm = DeviceInfoViewModel(client(FakeAdbTransport(throwOnOpen = java.io.IOException("refused"))), scope = this)

        vm.load()
        advanceUntilIdle()

        assertEquals("Connection to the device was lost", vm.state.value.message)
        assertEquals(null, vm.state.value.info)
    }

    @Test
    fun `load still shows the panel when individual commands fail`() = runTest {
        // e.g. `ip addr show wlan0` on a device with no wlan0, or a missing `nproc`.
        val vm = DeviceInfoViewModel(client(FakeAdbTransport(scriptedOutput = "not found\n", exitCode = 1)), scope = this)

        vm.load()
        advanceUntilIdle()

        assertTrue(vm.state.value.info != null, "expected a (blank) info panel, got ${vm.state.value}")
        assertEquals("", vm.state.value.message)
    }
}

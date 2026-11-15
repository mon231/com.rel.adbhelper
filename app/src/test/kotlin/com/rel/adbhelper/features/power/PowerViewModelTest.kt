package com.rel.adbhelper.features.power

import com.rel.adbhelper.core.adb.AdbClient
import com.rel.adbhelper.core.adb.FakeAdbTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** [Dispatchers.Unconfined] — see [com.rel.adbhelper.core.adb.AdbClientTest]'s identical
 * `client()` helper's doc. */
private fun client(transport: com.rel.adbhelper.core.adb.AdbTransport) =
    AdbClient(transport, watchdogDispatcher = Dispatchers.Unconfined)

@OptIn(ExperimentalCoroutinesApi::class)
class PowerViewModelTest {

    @Test
    fun `reboot reports success when the link drops before an exit code arrives`() = runTest {
        val vm = PowerViewModel(client(FakeAdbTransport(throwOnRead = java.io.IOException("Stream closed."))), scope = this)

        vm.reboot()
        advanceUntilIdle()

        assertEquals("Rebooting", vm.state.value.message)
    }

    @Test
    fun `reboot reports success when the shell ends without an exit status`() = runTest {
        val vm = PowerViewModel(client(FakeAdbTransport(omitExitMarker = true)), scope = this)

        vm.reboot()
        advanceUntilIdle()

        assertEquals("Rebooting", vm.state.value.message)
    }

    @Test
    fun `reboot still reports a command the target rejected`() = runTest {
        val vm = PowerViewModel(client(FakeAdbTransport(scriptedOutput = "reboot: Permission denied\n", exitCode = 1)), scope = this)

        vm.reboot()
        advanceUntilIdle()

        assertEquals("Command failed", vm.state.value.message)
    }

    @Test
    fun `a command that keeps the link still fails on a lost connection`() = runTest {
        val vm = PowerViewModel(client(FakeAdbTransport(throwOnRead = java.io.IOException("Stream closed."))), scope = this)

        vm.enableWirelessDebugging()
        advanceUntilIdle()

        assertEquals("Command failed", vm.state.value.message)
    }

    @Test
    fun `density outside the allowed range is rejected without touching the device`() = runTest {
        val transport = FakeAdbTransport()
        val vm = PowerViewModel(client(transport), scope = this)

        vm.setDensityInput("5")
        vm.applyDensity()
        advanceUntilIdle()

        assertEquals("Density must be between 72 and 1000", vm.state.value.message)
        assertEquals(null, vm.state.value.currentDensity)
    }

    @Test
    fun `density inside the allowed range is applied`() = runTest {
        val vm = PowerViewModel(client(FakeAdbTransport()), scope = this)

        vm.setDensityInput("420")
        vm.applyDensity()
        advanceUntilIdle()

        assertEquals("Density set to 420", vm.state.value.message)
    }
}

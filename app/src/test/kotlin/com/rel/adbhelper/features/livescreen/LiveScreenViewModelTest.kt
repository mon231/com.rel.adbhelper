package com.rel.adbhelper.features.livescreen

import com.rel.adbhelper.core.adb.AdbClient
import com.rel.adbhelper.core.adb.FakeAdbTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** [Dispatchers.Unconfined], not the production-default [Dispatchers.Default] — see
 * [com.rel.adbhelper.core.adb.AdbClientTest]'s identical `client()` helper's doc. */
private fun client(transport: com.rel.adbhelper.core.adb.AdbTransport) =
    AdbClient(transport, watchdogDispatcher = Dispatchers.Unconfined)

/** Smallest thing that passes for a whole PNG: signature, then the IEND chunk. */
private val PNG = byteArrayOf(
    0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    0, 0, 0, 0, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class LiveScreenViewModelTest {

    @Test
    fun `start repeatedly captures frames, one capture at a time, until stop is called`() = runTest {
        val transport = FakeAdbTransport(serviceOutputs = mapOf("exec:screencap -p" to PNG))
        val vm = LiveScreenViewModel(client(transport), scope = this)

        vm.start()
        advanceTimeBy(100)
        val framesAfter100ms = vm.state.value.frameCount
        assertTrue(framesAfter100ms > 1, "expected more than one frame captured in 100ms of 5ms-spaced polling, got $framesAfter100ms")

        vm.stop()
        val framesAtStop = vm.state.value.frameCount
        advanceTimeBy(100)
        assertEquals(framesAtStop, vm.state.value.frameCount, "expected no further captures once stopped")
    }

    @Test
    fun `an incomplete frame is skipped without an error`() = runTest {
        val truncated = PNG.copyOf(PNG.size - 1)
        val transport = FakeAdbTransport(serviceOutputs = mapOf("exec:screencap -p" to truncated))
        val vm = LiveScreenViewModel(client(transport), scope = this)

        vm.start()
        advanceTimeBy(100)
        vm.stop()

        assertEquals(0, vm.state.value.frameCount)
        assertEquals(null, vm.state.value.frame)
        assertEquals("", vm.state.value.error)
    }

    @Test
    fun `start surfaces a connection-lost error and stops polling when a capture fails`() = runTest {
        val transport = FakeAdbTransport(throwOnOpen = java.io.IOException("refused"))
        val vm = LiveScreenViewModel(client(transport), scope = this)

        vm.start()
        advanceUntilIdle()

        assertTrue(vm.state.value.error.isNotEmpty(), "expected an error message once the capture fails")
        val framesAtFailure = vm.state.value.frameCount
        advanceTimeBy(100)
        assertEquals(framesAtFailure, vm.state.value.frameCount, "expected polling to stop after a failure")
    }
}

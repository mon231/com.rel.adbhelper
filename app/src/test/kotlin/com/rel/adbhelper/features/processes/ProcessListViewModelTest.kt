package com.rel.adbhelper.features.processes

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
class ProcessListViewModelTest {

    @Test
    fun `load reports a lost connection instead of an empty list`() = runTest {
        val vm = ProcessListViewModel(client(FakeAdbTransport(throwOnOpen = java.io.IOException("refused"))), scope = this)

        vm.load()
        advanceUntilIdle()

        assertEquals("Connection to the device was lost", vm.state.value.message)
    }
}

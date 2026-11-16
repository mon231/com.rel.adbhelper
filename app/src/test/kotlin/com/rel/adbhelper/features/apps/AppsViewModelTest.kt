package com.rel.adbhelper.features.apps

import com.rel.adbhelper.core.adb.AdbClient
import com.rel.adbhelper.core.adb.FakeAdbTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** [Dispatchers.Unconfined], not the production-default [Dispatchers.Default]: under `runTest`'s
 * virtual time, a watchdog on a real dispatcher never gets its `delay` routed through the test's
 * virtual scheduler, so [kotlinx.coroutines.test.TestScope.advanceUntilIdle] returns as soon as
 * the virtual queue empties — even though the real-dispatcher watchdog coroutine (and the whole
 * suspended call waiting to join it) hasn't actually finished. Confirmed live: without this, the
 * test below silently measured just the first of three sequential calls and never caught that the
 * view model's own state had not finished loading. See [com.rel.adbhelper.core.adb.AdbClientTest]'s
 * identical `client()` helper for the same fix in its own tests. */
private fun client(transport: com.rel.adbhelper.core.adb.AdbTransport) =
    AdbClient(transport, watchdogDispatcher = Dispatchers.Unconfined)

@OptIn(ExperimentalCoroutinesApi::class)
class AppsViewModelTest {

    @Test
    fun `load fetches third-party, system, and disabled packages sequentially, not concurrently`() = runTest {
        // Deliberately the opposite of this test's own former name and assertion. Live-reproduced
        // against a real target: firing these three shell() calls at once over this app's one
        // shared multiplexed connection intermittently force-closed the two larger reads (system
        // and disabled, each 200+ packages) while the smallest (third-party) finished first — the
        // app silently showed an empty list with no error, since the failure was swallowed
        // instead of thrown. Same class of bug streamOpenMutex's own doc already covers for
        // concurrent opens, just resurfacing for concurrent reads on one connection instead.
        // Each of the three calls takes 1s on this (simulated) link — sequential, the whole load
        // should take ~3s, not ~1s.
        val transport = FakeAdbTransport(openDelayMillis = 1_000)
        val vm = AppsViewModel(client(transport), scope = this)

        vm.load()
        advanceUntilIdle()

        assertTrue(
            testScheduler.currentTime >= 3_000,
            "expected the three fetches to run one after another (~3s), took ${testScheduler.currentTime}ms " +
                "— running them concurrently reintroduces a live-reproduced connection-corruption bug",
        )
        assertTrue(!vm.state.value.loading, "expected load() to finish, got ${vm.state.value}")
    }

    @Test
    fun `load shows an error when the device is unreachable, not an empty app list`() = runTest {
        val transport = FakeAdbTransport(throwOnOpen = java.io.IOException("refused"))
        val vm = AppsViewModel(client(transport), scope = this)

        vm.load()
        advanceUntilIdle()

        assertEquals("Connection to the device was lost", vm.state.value.message)
        assertTrue(!vm.state.value.loading, "expected load() to finish, got ${vm.state.value}")
    }

    @Test
    fun `uninstall shows the remote command's own failure reason, not a generic message`() = runTest {
        // Live-reproduced: uninstalling YouTube (a system app) on a non-rooted target failed with
        // "Failure [only root can delete system app for a particular user]" on the wire, but the
        // UI only ever showed "Command failed" — indistinguishable from a real bug in this app,
        // instead of the real, expected-on-this-device reason.
        val transport = FakeAdbTransport(
            scriptedOutput = "Failure [only root can delete system app for a particular user]\n",
            exitCode = 1,
        )
        val vm = AppsViewModel(client(transport), scope = this)

        vm.uninstall(com.rel.adbhelper.core.apps.AppEntry(pkg = "com.google.android.youtube", isSystem = true, isEnabled = true))
        advanceUntilIdle()

        assertTrue(
            vm.state.value.message.contains("only root can delete system app"),
            "expected the real failure reason in the message, got '${vm.state.value.message}'",
        )
    }

    @Test
    fun `install failure shows pm's reason and the next action clears it`() {
        val vm = AppsViewModel(
            client(FakeAdbTransport(scriptedOutput = "Failure [INSTALL_FAILED_VERSION_DOWNGRADE]\n", exitCode = 1)),
            scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
        )

        vm.install("app.apk") { java.io.ByteArrayInputStream(byteArrayOf(1)) }
        val deadline = System.currentTimeMillis() + 5_000
        while (!vm.state.value.busy.startsWith("Install failed") && System.currentTimeMillis() < deadline) Thread.sleep(10)

        assertEquals("Install failed: Failure [INSTALL_FAILED_VERSION_DOWNGRADE]", vm.state.value.busy.trim())

        vm.clearCache(com.rel.adbhelper.core.apps.AppEntry(pkg = "a.b", isSystem = false, isEnabled = true))

        assertEquals("", vm.state.value.busy)
    }

    @Test
    fun `install removes the temporary apk when the push fails and shows the cause`() {
        val transport = FakeAdbTransport(throwOnOpen = java.io.IOException("refused"))
        val vm = AppsViewModel(client(transport), scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined))

        vm.install("app.apk") { java.io.ByteArrayInputStream(byteArrayOf(1)) }
        val deadline = System.currentTimeMillis() + 5_000
        while (!vm.state.value.busy.startsWith("Install failed") && System.currentTimeMillis() < deadline) Thread.sleep(10)

        val busy = vm.state.value.busy
        assertTrue("failed to write to /data/local/tmp/adbhelper-install.apk" in busy, busy)
        assertTrue("refused" in busy, busy)
        assertTrue(
            transport.openedServices.any { it.startsWith("shell:rm '/data/local/tmp/adbhelper-install.apk'") },
            "expected an rm of the temporary file, opened: ${transport.openedServices}",
        )
    }

    @Test
    fun `install removes the temporary apk when pm rejects it`() {
        val transport = FakeAdbTransport(scriptedOutput = "Failure [INSTALL_FAILED_INVALID_APK]\n", exitCode = 1)
        val vm = AppsViewModel(client(transport), scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined))

        vm.install("app.apk") { java.io.ByteArrayInputStream(byteArrayOf(1)) }
        val deadline = System.currentTimeMillis() + 5_000
        while (!vm.state.value.busy.startsWith("Install failed") && System.currentTimeMillis() < deadline) Thread.sleep(10)

        assertEquals("Install failed: Failure [INSTALL_FAILED_INVALID_APK]", vm.state.value.busy.trim())
        assertTrue(
            transport.openedServices.any { it.startsWith("shell:rm '/data/local/tmp/adbhelper-install.apk'") },
            "expected an rm of the temporary file, opened: ${transport.openedServices}",
        )
    }

    @Test
    fun `install streams the apk to the target and runs pm install`() {
        val transport = FakeAdbTransport()
        val vm = AppsViewModel(client(transport), scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined))

        vm.install("app.apk") { java.io.ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)) }
        val deadline = System.currentTimeMillis() + 5_000
        while (!vm.state.value.busy.startsWith("Install") || vm.state.value.busy.startsWith("Installing")) {
            if (System.currentTimeMillis() > deadline) break
            Thread.sleep(10)
        }

        assertEquals("Installed app.apk", vm.state.value.busy)
        assertEquals(listOf(4), transport.writtenByService["exec:cat > '/data/local/tmp/adbhelper-install.apk'"].orEmpty().map { it.size })
    }
}

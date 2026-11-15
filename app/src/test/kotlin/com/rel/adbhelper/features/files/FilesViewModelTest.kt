package com.rel.adbhelper.features.files

import com.rel.adbhelper.core.adb.AdbClient
import com.rel.adbhelper.core.adb.FakeAdbTransport
import com.rel.adbhelper.core.files.FileEntry
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

private fun entry(path: String, perms: String = "-rw-rw----") = FileEntry(
    name = path.substringAfterLast('/'), path = path,
    isDir = perms.startsWith("d"), isSymlink = perms.startsWith("l"),
    size = 0, perms = perms, owner = "root", mtime = "2026-10-26 13:55",
)

/** The service string [AdbClient.shell] opens for [command]. */
private fun shellService(command: String) = "shell:$command; echo __ADBHELPER_EXIT__:\$?"

@OptIn(ExperimentalCoroutinesApi::class)
class FilesViewModelTest {

    // A space and a single quote: the two characters that break naive shell interpolation.
    private val awkward = entry("/sdcard/it's a file.txt")
    private val awkwardQuoted = """'/sdcard/it'\''s a file.txt'"""

    @Test
    fun `delete quotes the path`() = runTest {
        val transport = FakeAdbTransport()
        val vm = FilesViewModel(client(transport), scope = this)

        vm.delete(awkward)
        advanceUntilIdle()

        assertTrue(shellService("rm -rf $awkwardQuoted") in transport.closedServices, "ran: ${transport.closedServices}")
    }

    @Test
    fun `rename quotes both paths`() = runTest {
        val transport = FakeAdbTransport()
        val vm = FilesViewModel(client(transport), scope = this)

        vm.rename(awkward, "it's new.txt")
        advanceUntilIdle()

        assertTrue(
            shellService("""mv $awkwardQuoted '/sdcard/it'\''s new.txt'""") in transport.closedServices,
            "ran: ${transport.closedServices}",
        )
    }

    @Test
    fun `paste quotes the source and targets the current directory`() = runTest {
        val transport = FakeAdbTransport()
        val vm = FilesViewModel(client(transport), scope = this)

        vm.copy(awkward)
        vm.paste()
        advanceUntilIdle()

        assertTrue(shellService("cp -r $awkwardQuoted '/sdcard'") in transport.closedServices, "ran: ${transport.closedServices}")
        assertEquals(null, vm.state.value.copiedFrom)
    }

    @Test
    fun `tapping a symlink opens it as a directory`() = runTest {
        // /sdcard is a symlink: its perms start with "l", so isDir is false.
        val transport = FakeAdbTransport(scriptedOutput = "drwxrws--- 2 u0_a212 media_rw 4096 2026-10-26 13:55 DCIM\n")
        val vm = FilesViewModel(client(transport), scope = this)

        vm.entryClick(entry("/sdcard", perms = "lrw-r--r--"))
        advanceUntilIdle()

        assertTrue(shellService("ls -la '/sdcard/'") in transport.closedServices, "ran: ${transport.closedServices}")
        assertEquals(listOf("/sdcard/DCIM"), vm.state.value.entries.map { it.path })
        assertEquals(null, vm.state.value.selectedForInfo)
    }

    @Test
    fun `tapping a symlink that cannot be listed shows its info instead`() = runTest {
        val transport = FakeAdbTransport(scriptedOutput = "ls: /etc/hosts/: Not a directory\n", exitCode = 1)
        val vm = FilesViewModel(client(transport), scope = this)
        val link = entry("/etc/hosts", perms = "lrw-r--r--")

        vm.entryClick(link)
        advanceUntilIdle()

        assertEquals(link, vm.state.value.selectedForInfo)
        assertEquals("", vm.state.value.message)
    }

    @Test
    fun `the root directory stays a slash`() = runTest {
        val transport = FakeAdbTransport()
        val vm = FilesViewModel(client(transport), scope = this)

        vm.open("/")
        advanceUntilIdle()

        assertEquals("/", vm.state.value.path)
        assertTrue(shellService("ls -la '/'") in transport.closedServices, "ran: ${transport.closedServices}")
    }
}

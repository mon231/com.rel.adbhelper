package com.rel.adbhelper.features.files

import com.rel.adbhelper.core.adb.AdbClient
import com.rel.adbhelper.core.adb.connectionLostMessage
import com.rel.adbhelper.core.adb.describe
import com.rel.adbhelper.core.adb.outputOrThrow
import com.rel.adbhelper.core.files.FileEntry
import com.rel.adbhelper.core.files.parseLsLa
import com.rel.adbhelper.core.shell.shellQuote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream

class FilesViewModel(
    private val client: AdbClient,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
) {
    data class State(
        val path: String = DEFAULT_ROOT,
        val entries: List<FileEntry> = emptyList(),
        val showHidden: Boolean = false,
        val loading: Boolean = false,
        val message: String = "",
        val copiedFrom: String? = null,
        val selectedForInfo: FileEntry? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** [link]: the symlink being followed, if any — when it turns out not to lead to a
     * directory, its info dialog is shown instead of a listing error. [keepMessage]: an internal
     * refresh after an action, which must not wipe that action's result message. */
    fun open(path: String, link: FileEntry? = null, keepMessage: Boolean = false) {
        scope.launch {
            _state.update { it.copy(loading = true, message = if (keepMessage) it.message else "") }
            try {
                // `ls -la <dir>` on a symlink lists the link itself, so always list with a
                // trailing slash to follow it (e.g. /sdcard -> /storage/self/primary). The root
                // stays "/" rather than trimming down to "".
                val dir = path.trimEnd('/').ifEmpty { "/" }
                val raw = client.shell(listCommand(dir)).outputOrThrow()
                _state.update { it.copy(
                    path = dir,
                    entries = parseLsLa(raw, dir),
                    loading = false,
                ) }
            } catch (t: Throwable) {
                _state.update {
                    if (link != null) {
                        it.copy(loading = false, selectedForInfo = link)
                    } else {
                        it.copy(
                            loading = false,
                            message = "Failed to list ${path}: ${t.message ?: "unknown error"}",
                        )
                    }
                }
            }
        }
    }

    fun up() {
        val dir = _state.value.path.trimEnd('/')
        val parent = dir.substringBeforeLast('/', "")
        open(parent.ifEmpty { "/" })
    }

    fun toggleHidden() {
        _state.update { it.copy(showHidden = !it.showHidden) }
    }

    fun entryClick(entry: FileEntry) {
        if (entry.isSymlink) {
            // A link's own perms never say whether it leads to a directory (/sdcard does), so
            // just try to list it.
            open(entry.path, link = entry)
        } else if (entry.isDir) {
            open(entry.path)
        } else {
            _state.update { it.copy(selectedForInfo = entry) }
        }
    }

    fun dismissInfo() {
        _state.update { it.copy(selectedForInfo = null) }
    }

    fun createFolder(name: String) = runCommand("mkdir -p ${shellQuote(join(name))}", "Folder created")
    fun createFile(name: String) = runCommand("touch ${shellQuote(join(name))}", "File created")
    fun rename(entry: FileEntry, newName: String) =
        runCommand("mv ${shellQuote(entry.path)} ${shellQuote(join(newName))}", "Renamed")
    fun delete(entry: FileEntry) =
        runCommand("rm -rf ${shellQuote(entry.path)}", "Deleted")
    fun copy(entry: FileEntry) {
        _state.update { it.copy(copiedFrom = entry.path, message = "Copied ${entry.name} — open the destination and paste") }
    }
    fun paste() {
        val from = _state.value.copiedFrom ?: return
        // Only a copy that worked uses the clipboard up; after a failure it can be retried.
        runCommand("cp -r ${shellQuote(from)} ${shellQuote(_state.value.path)}", "Pasted") {
            _state.update { it.copy(copiedFrom = null) }
        }
    }

    /** [openInput] supplies the picked file's stream (null when it cannot be read); it is opened and
     * read on [Dispatchers.IO] and streamed to the target, never loaded into memory whole. */
    fun upload(path: String, openInput: () -> InputStream?) {
        scope.launch {
            try {
                val input = withContext(Dispatchers.IO) { openInput() }
                if (input == null) {
                    _state.update { it.copy(message = "Could not read the selected file") }
                    return@launch
                }
                input.use { client.push(path, it) }
                _state.update { it.copy(message = "Uploaded ${path.substringAfterLast('/')}") }
                open(_state.value.path, keepMessage = true)
            } catch (t: Throwable) {
                _state.update { it.copy(message = "Upload failed: ${t.describe()}") }
            }
        }
    }

    fun download(entry: FileEntry, saveContext: android.content.Context) =
        download(entry, saveContext.getExternalFilesDir("Downloads") ?: saveContext.filesDir)

    /** Writes to a `.part` file and only renames it to a free name in [dir] once the whole file
     * arrived, so a failed (or repeated) download never touches an earlier downloaded file. */
    fun download(entry: FileEntry, dir: java.io.File) {
        scope.launch {
            val part = uniqueFile(dir, "${entry.name}.part")
            try {
                // Streams chunk-by-chunk straight to disk — entry.size can be arbitrarily large
                // (a video, an APK), so never buffer the whole file in memory first.
                part.outputStream().use { out ->
                    client.pullTo(entry.path) { chunk -> out.write(chunk) }
                }
                val file = uniqueFile(dir, entry.name)
                if (!part.renameTo(file)) throw java.io.IOException("could not save ${file.name}")
                _state.update { it.copy(message = "Downloaded to ${file.absolutePath}") }
            } catch (t: Throwable) {
                part.delete()
                _state.update { it.copy(message = "Download failed: ${t.describe()}") }
            }
        }
    }

    private fun join(name: String) = "${_state.value.path.trimEnd('/')}/$name"

    private fun runCommand(command: String, successMessage: String, onSuccess: () -> Unit = {}) {
        scope.launch {
            try {
                val result = client.shell(command)
                if (result is com.rel.adbhelper.core.adb.AdbResult.Success) {
                    onSuccess()
                    _state.update { it.copy(message = successMessage) }
                } else {
                    _state.update { it.copy(
                        message = if (result is com.rel.adbhelper.core.adb.AdbResult.TransportUnreachable) {
                            connectionLostMessage(result.cause)
                        } else {
                            "Command failed"
                        },
                    ) }
                }
            } catch (t: Throwable) {
                _state.update { it.copy(message = connectionLostMessage(t)) }
            }
            open(_state.value.path, keepMessage = true)
        }
    }

    companion object {
        /**
         * `-N`: the `shell:` service runs under a terminal, and toybox `ls` on a terminal escapes
         * spaces with a backslash (`my\ file`) — live-reproduced: every name with a space was
         * listed, and then addressed, with a literal backslash, so it could not be opened,
         * renamed, deleted or downloaded. An `ls` too old to know `-N` falls back to plain `-la`.
         */
        internal fun listCommand(dir: String): String {
            val target = shellQuote("${dir.trimEnd('/')}/")
            return "ls -laN $target 2>/dev/null || ls -la $target"
        }

        const val DEFAULT_ROOT = "/sdcard"
    }
}

/** [name] in [dir], or `name (1).ext`, `name (2).ext`, ... when that file already exists, so a
 * download never silently overwrites an earlier one. */
internal fun uniqueFile(dir: java.io.File, name: String): java.io.File {
    var candidate = java.io.File(dir, name)
    if (!candidate.exists()) return candidate
    val stem = name.substringBeforeLast('.', name)
    val ext = name.substring(stem.length)
    var n = 1
    while (candidate.exists()) candidate = java.io.File(dir, "$stem ($n)$ext").also { n++ }
    return candidate
}

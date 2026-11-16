package com.rel.adbhelper.core.adb

/** Distinguishes a successful command from a command the target rejected from the transport itself being unreachable — the three outcomes callers need to show different messages for. */
sealed class AdbResult {
    data class Success(val output: String) : AdbResult()
    data class CommandFailed(val message: String) : AdbResult()
    data class TransportUnreachable(val cause: Throwable) : AdbResult()
}

/** The command's output, or an exception for either failure — so a dead link reaches the caller's
 * error path instead of reading as "the device returned nothing". */
fun AdbResult.outputOrThrow(): String = when (this) {
    is AdbResult.Success -> output
    is AdbResult.CommandFailed -> throw java.io.IOException(message)
    is AdbResult.TransportUnreachable -> throw java.io.IOException(connectionLostMessage(cause), cause)
}

/** The sentence shown when the link failed, with why in brackets, e.g.
 * `Connection to the device was lost (timed out opening shell:wm density)`. */
fun connectionLostMessage(cause: Throwable): String =
    "Connection to the device was lost (${cause.describe()})"

/** The messages of this failure and of its causes, joined with ": " — [AdbClient]'s wrappers say
 * what was being done ("failed to write to /x"), the cause says why. A message already contained
 * in an earlier one (a wrapper that copied its cause's text) is not repeated. */
fun Throwable.describe(): String {
    val parts = mutableListOf<String>()
    var t: Throwable? = this
    var depth = 0
    while (t != null && depth++ < 8) {
        val m = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
        if (parts.none { m in it }) parts += m
        t = t.cause
    }
    return parts.joinToString(": ")
}

/**
 * True when the target itself rejected the command (non-zero exit, e.g. permission denied) —
 * as opposed to the link dropping before any exit status arrived, which is what a command that
 * cuts the connection (reboot, restarting adbd) looks like when it works. [AdbClient.shell]
 * reports that second case either as [AdbResult.TransportUnreachable] or as a
 * [AdbResult.CommandFailed] carrying [NO_EXIT_STATUS].
 */
internal fun AdbResult.isRejection(): Boolean = this is AdbResult.CommandFailed && message != NO_EXIT_STATUS

/** [AdbResult.CommandFailed]'s message for a shell stream that ended without the exit marker;
 * [AdbClient] produces it and [isRejection] recognises it (pinned by `PowerViewModelTest`). */
internal const val NO_EXIT_STATUS = "remote shell closed without an exit status"

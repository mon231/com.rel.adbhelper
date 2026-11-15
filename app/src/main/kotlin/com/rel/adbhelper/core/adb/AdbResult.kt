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
    is AdbResult.TransportUnreachable -> throw java.io.IOException("Connection to the device was lost", cause)
}

package com.rel.adbhelper.core.logs

/** Level of a line that is not a log record (e.g. `--------- beginning of main`). */
const val RAW_LEVEL = ' '

/** One `logcat -v threadtime` line. A line that does not match the format is kept whole in
 * [message] with [level] = [RAW_LEVEL] and the other fields empty/zero. */
data class LogLine(
    val time: String,
    val pid: Int,
    val tid: Int,
    val level: Char,
    val tag: String,
    val message: String,
)

/**
 * `MM-DD HH:MM:SS.mmm  PID  TID L TAG: message`. The tag is everything up to the first colon
 * that is followed by a space (or ends the line), so tags containing spaces or bare colons
 * (`Foo:Bar`) survive; logcat pads short tags with trailing spaces, which are trimmed.
 */
private val THREADTIME = Regex(
    """^(\d\d-\d\d \d\d:\d\d:\d\d\.\d{3})\s+(\d+)\s+(\d+)\s+([VDIWEF])\s+(.*?)\s*:(?: |$)(.*)$""",
)

fun parseLogLine(line: String): LogLine {
    val match = THREADTIME.find(line) ?: return LogLine("", 0, 0, RAW_LEVEL, "", line)
    val (time, pid, tid, level, tag, message) = match.destructured
    return LogLine(
        time = time,
        pid = pid.toIntOrNull() ?: 0,
        tid = tid.toIntOrNull() ?: 0,
        level = level[0],
        tag = tag,
        message = message,
    )
}

private const val LEVEL_ORDER = "VDIWEF"

/**
 * True when the line is at least [minLevel] (V<D<I<W<E<F) and [query], if not blank, occurs
 * case-insensitively in its tag or message. Raw (non-record) lines have no level, so only the
 * query applies to them.
 */
fun LogLine.matches(minLevel: Char, query: String): Boolean {
    if (level != RAW_LEVEL && LEVEL_ORDER.indexOf(level) < LEVEL_ORDER.indexOf(minLevel)) return false
    val q = query.trim()
    return q.isEmpty() || tag.contains(q, ignoreCase = true) || message.contains(q, ignoreCase = true)
}

fun filterLogLines(lines: List<LogLine>, minLevel: Char, query: String): List<LogLine> =
    lines.filter { it.matches(minLevel, query) }

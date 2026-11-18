package com.rel.adbhelper.core.logs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LogcatParserTest {

    // Fixture: `logcat -v threadtime` output in the shape a real device produces, trimmed.
    private val fixture = """
--------- beginning of main
11-15 09:41:02.118  1423  1479 I ActivityManager: Start proc 9120:com.android.settings/1000 for top-activity
11-15 09:41:02.305  9120  9120 D ViewRootImpl: setView = com.android.internal.policy.DecorView@a1b2c3
11-15 09:41:02.412   612   640 W chatty  : uid=1000(system) Binder:612_3 expire 4 lines
11-15 09:41:03.001  9120  9144 E AndroidRuntime: FATAL EXCEPTION: main
11-15 09:41:03.002  9120  9144 E AndroidRuntime: 	at com.example.Main.run(Main.kt:42)
11-15 09:41:03.050 12345 12399 V My App Tag: verbose detail
11-15 09:41:03.060   301   301 F libc    : Fatal signal 11 (SIGSEGV), code 1
    """.trimIndent()

    private val lines = fixture.lines().map(::parseLogLine)

    @Test
    fun `a threadtime line is split into its fields`() {
        assertEquals(
            LogLine(
                time = "11-15 09:41:02.118",
                pid = 1423,
                tid = 1479,
                level = 'I',
                tag = "ActivityManager",
                message = "Start proc 9120:com.android.settings/1000 for top-activity",
            ),
            lines[1],
        )
    }

    @Test
    fun `a line that is not a log record is kept raw with a neutral level`() {
        assertEquals(LogLine("", 0, 0, RAW_LEVEL, "", "--------- beginning of main"), lines[0])
        assertEquals(RAW_LEVEL, parseLogLine("").level)
    }

    @Test
    fun `tag padding is trimmed and colons inside the message are left alone`() {
        assertEquals("chatty", lines[3].tag)
        assertEquals("uid=1000(system) Binder:612_3 expire 4 lines", lines[3].message)
        assertEquals("FATAL EXCEPTION: main", lines[4].message)
        assertEquals("\tat com.example.Main.run(Main.kt:42)", lines[5].message)
    }

    @Test
    fun `tags with spaces or bare colons are kept whole`() {
        assertEquals("My App Tag", lines[6].tag)
        assertEquals("verbose detail", lines[6].message)

        val colonTag = parseLogLine("11-15 09:41:03.070   301   301 I Foo:Bar: hello")
        assertEquals("Foo:Bar", colonTag.tag)
        assertEquals("hello", colonTag.message)
    }

    @Test
    fun `an empty message still parses as a record`() {
        val line = parseLogLine("11-15 09:41:03.070   301   301 D Tag:")
        assertEquals('D', line.level)
        assertEquals("Tag", line.tag)
        assertEquals("", line.message)
    }

    @Test
    fun `minimum level keeps that level and above, plus raw lines`() {
        assertEquals(lines.size, filterLogLines(lines, 'V', "").size)
        assertEquals(
            listOf(RAW_LEVEL, 'W', 'E', 'E', 'F'),
            filterLogLines(lines, 'W', "").map { it.level },
        )
        assertEquals(listOf(RAW_LEVEL, 'E', 'E', 'F'), filterLogLines(lines, 'E', "").map { it.level })
    }

    @Test
    fun `query matches tag or message, ignoring case`() {
        assertEquals(listOf("ActivityManager"), filterLogLines(lines, 'V', "activitymanager").map { it.tag })
        assertEquals(listOf("libc"), filterLogLines(lines, 'V', "sigsegv").map { it.tag })
        assertEquals(listOf("--------- beginning of main"), filterLogLines(lines, 'V', "BEGINNING").map { it.message })
        // The pid is not searched.
        assertEquals(emptyList<LogLine>(), filterLogLines(lines, 'V', "12399"))
    }

    @Test
    fun `level and query combine`() {
        assertEquals(
            listOf("FATAL EXCEPTION: main"),
            filterLogLines(lines, 'E', "exception").map { it.message },
        )
    }
}

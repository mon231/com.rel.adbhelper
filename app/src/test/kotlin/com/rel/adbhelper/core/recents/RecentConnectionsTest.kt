package com.rel.adbhelper.core.recents

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RecentConnectionsTest {

    @Test
    fun `a new entry goes to the top`() {
        assertEquals(listOf("c:1", "a:1", "b:1"), withRecent(listOf("a:1", "b:1"), "c:1"))
    }

    @Test
    fun `re-adding an existing entry moves it to the top without duplicating it`() {
        assertEquals(listOf("b:1", "a:1", "c:1"), withRecent(listOf("a:1", "b:1", "c:1"), "b:1"))
    }

    @Test
    fun `the list is capped and drops the oldest entry`() {
        val full = (1..MAX_RECENTS).map { "host$it:5555" }
        val result = withRecent(full, "new:5555")
        assertEquals(MAX_RECENTS, result.size)
        assertEquals("new:5555", result.first())
        assertEquals(full.dropLast(1), result.drop(1))
    }
}

package com.rel.adbhelper.core.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MdnsMergeTest {
    @Test
    fun `merge de-duplicates and sorts sweep and mdns results`() {
        val merged = mergeScanResults(listOf("10.0.0.9:5555"), listOf("10.0.0.2:37000", "10.0.0.9:5555"))
        assertEquals(listOf("10.0.0.2:37000", "10.0.0.9:5555"), merged)
    }

    @Test
    fun `prefill only when exactly one service and field blank`() {
        assertEquals("10.0.0.2:4000", pairingPrefill(listOf("10.0.0.2:4000"), ""))
        assertNull(pairingPrefill(listOf("10.0.0.2:4000"), "1.2.3.4:5"))
        assertNull(pairingPrefill(listOf("a:1", "b:2"), ""))
        assertNull(pairingPrefill(emptyList(), ""))
    }
}

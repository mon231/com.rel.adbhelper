package com.rel.adbhelper.core.net

import com.rel.adbhelper.core.net.LanScanner.subnetHosts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class LanScannerTest {

    @Test
    fun `subnetHosts enumerates every other usable host of the slash-24`() {
        val hosts = subnetHosts("10.0.2.16")
        assertEquals(253, hosts.size)
        assertEquals("10.0.2.1", hosts.first())
        assertEquals("10.0.2.254", hosts.last())
    }

    @Test
    fun `subnetHosts leaves out the device's own address`() {
        assertFalse("10.0.2.16" in subnetHosts("10.0.2.16"))
    }

    @Test
    fun `formatTarget produces a host colon port string parseHostPort accepts`() {
        assertEquals("10.0.2.15:5555", LanScanner.formatTarget("10.0.2.15", 5555))
    }
}

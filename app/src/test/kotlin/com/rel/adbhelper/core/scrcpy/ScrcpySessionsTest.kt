package com.rel.adbhelper.core.scrcpy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ScrcpySessionsTest {
    @Test
    fun `a plain host and port gets its own network connection`() {
        assertEquals("10.0.0.5" to 5555, networkHostPort("10.0.0.5:5555"))
    }

    @Test
    fun `usb and own-stack targets use the session client`() {
        assertNull(networkHostPort("usb:Pixel 7"))
        assertNull(networkHostPort("usb:dev:80"))
        assertNull(networkHostPort("raw:127.0.0.1:5557"))
        assertNull(networkHostPort(null))
    }
}

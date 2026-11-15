package com.rel.adbhelper.core.image

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PngTest {

    private val png = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        1, 2, 3,
        0, 0, 0, 0, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
    )

    @Test
    fun `isCompletePng accepts a PNG that starts with the signature and ends with IEND`() {
        assertTrue(isCompletePng(png))
    }

    @Test
    fun `isCompletePng rejects truncated, non-PNG and empty data`() {
        assertFalse(isCompletePng(png.copyOf(png.size - 1)), "truncated")
        assertFalse(isCompletePng(png.copyOfRange(1, png.size)), "missing signature")
        assertFalse(isCompletePng(png.copyOf(8)), "signature only")
        assertFalse(isCompletePng(ByteArray(0)), "empty")
    }
}

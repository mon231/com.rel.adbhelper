package com.rel.adbhelper.core.image

private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
/** The IEND chunk that ends every PNG (length 0, type, CRC). */
internal val PNG_IEND = byteArrayOf(0, 0, 0, 0, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte())

/**
 * Whether [bytes] is a whole PNG: the 8-byte signature up front and the IEND chunk at the very
 * end. [AdbClient.raw] treats a stalled read as a normal end of data, so a capture can come back
 * cut short without any error — this is the only way to tell.
 */
internal fun isCompletePng(bytes: ByteArray): Boolean =
    bytes.size >= PNG_SIGNATURE.size + PNG_IEND.size &&
        bytes.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE) &&
        bytes.copyOfRange(bytes.size - PNG_IEND.size, bytes.size).contentEquals(PNG_IEND)

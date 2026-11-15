package com.rel.adbhelper.core.adb

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.util.Date
import java.util.concurrent.TimeUnit

class KeyChecksTest {
    private fun rsa() = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()

    @Test
    fun `matching pair is accepted`() {
        val pair = rsa()
        assertTrue(keysMatch(pair.private, pair.public))
    }

    @Test
    fun `mismatched pair is rejected`() {
        assertFalse(keysMatch(rsa().private, rsa().public))
    }

    @Test
    fun `reissue when expired or near expiry only`() {
        val now = Date()
        fun inDays(d: Long) = Date(now.time + TimeUnit.DAYS.toMillis(d))
        assertTrue(certNeedsReissue(inDays(-1), now))
        assertTrue(certNeedsReissue(inDays(29), now))
        assertFalse(certNeedsReissue(inDays(31), now))
    }
}

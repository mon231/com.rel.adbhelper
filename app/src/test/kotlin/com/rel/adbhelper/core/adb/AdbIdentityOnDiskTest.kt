package com.rel.adbhelper.core.adb

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.cert.X509Certificate

class AdbIdentityOnDiskTest {
    @TempDir
    lateinit var dir: File

    private val logs = mutableListOf<Pair<Boolean, String>>()
    private fun load() = AdbKeyConnectionManager.loadOrGenerateFromDisk(dir) { warn, msg -> logs += warn to msg }
    private val keyFile get() = File(dir, "adb_private.key")
    private val certFile get() = File(dir, "adb_cert.pem")

    @Test
    fun `no files generates a pair and a second load keeps it`() {
        val (key, _) = load()
        assertTrue(keyFile.exists() && certFile.exists())
        val (again, _) = load()
        assertArrayEquals(key.encoded, again.encoded)
        assertTrue(logs.isEmpty())
    }

    @Test
    fun `missing certificate is re-issued from the same key`() {
        val (key, _) = load()
        certFile.delete()
        val (kept, cert) = load()
        assertArrayEquals(key.encoded, kept.encoded)
        assertArrayEquals(key.encoded, keyFile.readBytes())
        assertTrue(keysMatch(kept, cert.publicKey))
        assertTrue(certFile.exists())
        assertEquals(listOf(false), logs.map { it.first })
    }

    @Test
    fun `garbage certificate is re-issued from the same key`() {
        val (key, _) = load()
        certFile.writeBytes(byteArrayOf(1, 2, 3))
        val (kept, cert) = load()
        assertArrayEquals(key.encoded, kept.encoded)
        assertTrue(keysMatch(kept, cert.publicKey))
        assertFalse(File(dir, "adb_private.key.bak").exists())
    }

    @Test
    fun `certificate of another key is replaced and the key is kept`() {
        val (key, _) = load()
        val otherDir = File(dir, "other").also { it.mkdir() }
        val (_, otherCert) = AdbKeyConnectionManager.loadOrGenerateFromDisk(otherDir) { _, _ -> }
        certFile.writeBytes(otherCert.encoded)
        val (kept, cert) = load()
        assertArrayEquals(key.encoded, kept.encoded)
        assertTrue(keysMatch(kept, cert.publicKey))
        assertArrayEquals(cert.encoded, certFile.readBytes())
    }

    @Test
    fun `unparsable key is regenerated and the old files are kept as backups`() {
        val (_, oldCert) = load()
        val junk = byteArrayOf(9, 9, 9)
        keyFile.writeBytes(junk)
        File(dir, "adb_private.key.bak").writeBytes(byteArrayOf(7)) // a previous backup is replaced
        val (key, cert) = load()
        assertTrue(keysMatch(key, cert.publicKey))
        assertArrayEquals(key.encoded, keyFile.readBytes())
        assertArrayEquals(junk, File(dir, "adb_private.key.bak").readBytes())
        assertArrayEquals(oldCert.encoded, File(dir, "adb_cert.pem.bak").readBytes())
        assertEquals(listOf(true), logs.map { it.first })
    }

    @Test
    fun `valid pair is left untouched`() {
        val (_, cert) = load()
        val before = certFile.readBytes()
        val (_, again) = load()
        assertArrayEquals(before, certFile.readBytes())
        assertEquals((cert as X509Certificate).serialNumber, (again as X509Certificate).serialNumber)
    }
}

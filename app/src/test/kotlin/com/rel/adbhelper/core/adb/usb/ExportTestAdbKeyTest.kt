package com.rel.adbhelper.core.adb.usb

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Not a real test: writes [RealAdbdIntegrationTest]'s public key in adb's `adb_keys` line format
 * to the file named by `ADBHELPER_EXPORT_PUBKEY`, so CI can pre-authorise it on an emulator
 * without anyone tapping Allow. Skipped unless that variable (or system property) is set.
 */
class ExportTestAdbKeyTest {
    @Test
    fun `export public key in adb format`() {
        val path = (System.getProperty(VAR) ?: System.getenv(VAR))?.takeIf { it.isNotBlank() }
        assumeTrue(path != null, "set $VAR=<file> to export the test key")
        val line = String(RealAdbdIntegrationTest.persistentIdentity().publicKeyPayload(), Charsets.US_ASCII).trimEnd('\u0000')
        File(path!!).writeText(line + "\n")
    }

    private companion object {
        const val VAR = "ADBHELPER_EXPORT_PUBKEY"
    }
}

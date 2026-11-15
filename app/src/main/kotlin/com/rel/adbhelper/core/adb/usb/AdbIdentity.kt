package com.rel.adbhelper.core.adb.usb

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/** What the ADB handshake needs from this app's key pair. */
interface AdbIdentity {
    /** Signature over the device's AUTH token, for AUTH SIGNATURE. */
    fun sign(token: ByteArray): ByteArray

    /** Payload of AUTH RSAPUBLICKEY: the public key in adb's own format, base64, a space, a
     * user name, NUL. */
    fun publicKeyPayload(): ByteArray
}

/**
 * [AdbIdentity] backed by an RSA-2048 key pair — the only key size adb's public-key format can
 * carry.
 *
 * `libadb-android` has the same two operations (`AndroidPubkey.adbAuthSign` / `encode`), but in
 * a package-private class, so they are re-implemented here; `AdbIdentityTest` checks this
 * implementation produces byte-identical output to the library's.
 */
class RsaAdbIdentity(
    private val privateKey: PrivateKey,
    private val publicKey: RSAPublicKey,
    private val name: String = "adbhelper@android",
) : AdbIdentity {

    /**
     * adb treats the 20-byte token as an already-computed SHA-1 digest and signs it with
     * PKCS#1 v1.5: `NONEwithRSA` applies the padding, and the DigestInfo prefix that a
     * `SHA1withRSA` signature would carry is prepended by hand.
     */
    override fun sign(token: ByteArray): ByteArray {
        if (token.size != AdbWire.AUTH_TOKEN_LENGTH) {
            throw AdbProtocolException("ADB auth token is ${token.size} bytes, expected ${AdbWire.AUTH_TOKEN_LENGTH}")
        }
        return Signature.getInstance("NONEwithRSA").run {
            initSign(privateKey)
            update(SHA1_DIGEST_INFO_PREFIX)
            update(token)
            sign()
        }
    }

    override fun publicKeyPayload(): ByteArray {
        val encoded = Base64.getEncoder().encodeToString(encodeAdbPublicKey(publicKey))
        return "$encoded $name\u0000".toByteArray(Charsets.US_ASCII)
    }

    companion object {
        /** ASN.1 DigestInfo header for a SHA-1 digest (RFC 8017, section 9.2 note 1). */
        private val SHA1_DIGEST_INFO_PREFIX = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
        )

        private const val MODULUS_BYTES = 2048 / 8
        private const val MODULUS_WORDS = MODULUS_BYTES / 4

        /** Size of the binary key structure: three 32-bit fields and two 2048-bit numbers. */
        const val ENCODED_KEY_LENGTH = 3 * 4 + 2 * MODULUS_BYTES

        /**
         * adb's `RSAPublicKey` struct (AOSP `libcrypto_utils/android_pubkey.cpp`), little-endian:
         * modulus length in 32-bit words, `-1 / n[0] mod 2^32`, the modulus, `R^2 mod n` with
         * `R = 2^2048` (both precomputed for Montgomery multiplication), and the public exponent.
         */
        fun encodeAdbPublicKey(key: RSAPublicKey): ByteArray {
            val modulus = key.modulus
            require(modulus.bitLength() == MODULUS_BYTES * 8) {
                "adb needs a 2048-bit RSA key, this one has ${modulus.bitLength()} bits"
            }
            val wordBase = BigInteger.ONE.shiftLeft(32)
            val n0inv = wordBase.subtract(modulus.mod(wordBase).modInverse(wordBase))
            val rSquared = BigInteger.ONE.shiftLeft(2 * MODULUS_BYTES * 8).mod(modulus)
            return ByteBuffer.allocate(ENCODED_KEY_LENGTH).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(MODULUS_WORDS)
                .putInt(n0inv.toInt())
                .put(littleEndian(modulus))
                .put(littleEndian(rSquared))
                .putInt(key.publicExponent.intValueExact())
                .array()
        }

        /** [value] as exactly [MODULUS_BYTES] little-endian bytes, zero-padded at the top. */
        private fun littleEndian(value: BigInteger): ByteArray {
            val bigEndian = value.toByteArray() // may carry one leading sign byte, or be short
            val out = ByteArray(MODULUS_BYTES)
            val count = minOf(bigEndian.size, MODULUS_BYTES)
            for (i in 0 until count) out[i] = bigEndian[bigEndian.size - 1 - i]
            return out
        }
    }
}

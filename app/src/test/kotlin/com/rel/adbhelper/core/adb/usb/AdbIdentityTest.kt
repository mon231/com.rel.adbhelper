package com.rel.adbhelper.core.adb.usb

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import javax.crypto.Cipher

class AdbIdentityTest {

    private val keyPair: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val publicKey = keyPair.public as RSAPublicKey
    private val identity = RsaAdbIdentity(keyPair.private, publicKey)

    private val sha1DigestInfoPrefix = hex("3021300906052b0e03021a05000414")

    @Test
    fun `sign produces a PKCS1 signature over the DigestInfo of the token`() {
        val token = ByteArray(20) { (it * 7 + 1).toByte() }
        val signature = identity.sign(token)
        assertEquals(256, signature.size)

        // Decrypting with the public key gives the PKCS#1 v1.5 block: 00 01 FF..FF 00 | DigestInfo | token.
        val block = Cipher.getInstance("RSA/ECB/NoPadding").run {
            init(Cipher.DECRYPT_MODE, publicKey)
            doFinal(signature)
        }
        val expectedTail = sha1DigestInfoPrefix + token
        assertEquals(0x00, block[0].toInt())
        assertEquals(0x01, block[1].toInt())
        val separator = 256 - expectedTail.size - 1
        for (i in 2 until separator) assertEquals(0xFF.toByte(), block[i], "padding byte $i")
        assertEquals(0x00, block[separator].toInt())
        assertArrayEquals(expectedTail, block.copyOfRange(separator + 1, 256))

        // The same through the JCE PKCS1 cipher: it strips the padding for us.
        val unpadded = Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
            init(Cipher.DECRYPT_MODE, publicKey)
            doFinal(signature)
        }
        assertArrayEquals(expectedTail, unpadded)
    }

    @Test
    fun `sign rejects a token that is not 20 bytes`() {
        assertThrows(AdbProtocolException::class.java) { identity.sign(ByteArray(19)) }
        assertThrows(AdbProtocolException::class.java) { identity.sign(ByteArray(21)) }
        assertThrows(AdbProtocolException::class.java) { identity.sign(ByteArray(0)) }
    }

    @Test
    fun `adb public key has the documented layout`() {
        val encoded = RsaAdbIdentity.encodeAdbPublicKey(publicKey)
        assertEquals(524, encoded.size)
        val buffer = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(64, buffer.getInt(0))

        val modulus = publicKey.modulus
        val n0inv = buffer.getInt(4).toLong() and 0xFFFFFFFFL
        val n0 = modulus.toLong() and 0xFFFFFFFFL
        assertEquals(0xFFFFFFFFL, (n0inv * n0) and 0xFFFFFFFFL, "n0inv * n[0] == -1 mod 2^32")

        assertEquals(modulus, readLittleEndian(encoded, 8, 256))
        val rSquared = BigInteger.ONE.shiftLeft(4096).mod(modulus)
        assertEquals(rSquared, readLittleEndian(encoded, 8 + 256, 256))
        assertEquals(65537, buffer.getInt(8 + 512))
    }

    @Test
    fun `adb public key rejects other key sizes`() {
        val small = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair().public as RSAPublicKey
        assertThrows(IllegalArgumentException::class.java) { RsaAdbIdentity.encodeAdbPublicKey(small) }
    }

    @Test
    fun `public key payload is base64 key, name and a terminating NUL`() {
        val payload = identity.publicKeyPayload()
        assertEquals(0, payload.last().toInt())
        val text = String(payload, Charsets.US_ASCII)
        assertTrue(text.endsWith(" adbhelper@android\u0000"), text.takeLast(30))
        val base64 = text.substringBefore(' ')
        assertArrayEquals(RsaAdbIdentity.encodeAdbPublicKey(publicKey), Base64.getDecoder().decode(base64))
        assertTrue('\n' !in text)
    }

    @Test
    fun `output is byte-identical to the libadb-android AndroidPubkey`() {
        val library = Class.forName("io.github.muntashirakon.adb.AndroidPubkey")

        val encode = library.getDeclaredMethod("encode", RSAPublicKey::class.java).apply { isAccessible = true }
        assertArrayEquals(encode.invoke(null, publicKey) as ByteArray, RsaAdbIdentity.encodeAdbPublicKey(publicKey))

        val sign = library.getDeclaredMethod("adbAuthSign", java.security.PrivateKey::class.java, ByteArray::class.java)
            .apply { isAccessible = true }
        val token = ByteArray(20) { (255 - it * 11).toByte() }
        assertArrayEquals(sign.invoke(null, keyPair.private as RSAPrivateKey, token) as ByteArray, identity.sign(token))
    }

    private fun readLittleEndian(bytes: ByteArray, offset: Int, length: Int): BigInteger =
        BigInteger(1, bytes.copyOfRange(offset, offset + length).reversedArray())
}

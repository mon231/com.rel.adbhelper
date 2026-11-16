package com.rel.adbhelper.core.adb.usb

import com.rel.adbhelper.core.adb.AdbClient
import com.rel.adbhelper.core.adb.AdbResult
import com.rel.adbhelper.core.adb.AdbStream
import com.rel.adbhelper.core.image.isCompletePng
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.io.File
import java.io.IOException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Random
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs the production USB stack (AdbClient, UsbAdbTransport, AdbConnection) against a REAL adbd
 * reached over plain TCP, which speaks the same protocol as USB. Skipped unless the system
 * property or environment variable `ADBHELPER_REAL_ADBD` is set to `host:port`, e.g.
 * `ADBHELPER_REAL_ADBD=127.0.0.1:5557 bash ./gradlew :app:testDebugUnitTest --rerun-tasks`.
 *
 * The key is kept in `~/.adbhelper-test/adbkey.pk8` (created on first run) so the device asks
 * for authorisation only once; the first run waits up to a minute for someone to accept it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class RealAdbdIntegrationTest {

    private val address: String? =
        (System.getProperty(ENV) ?: System.getenv(ENV))?.trim()?.takeIf { it.isNotEmpty() }
    private val awaitingAuthorisation = AtomicInteger()
    private lateinit var transport: UsbAdbTransport
    private lateinit var client: AdbClient

    @BeforeAll
    fun setUp() {
        assumeTrue(address != null, "set $ENV=host:port to run against a real adbd")
        val host = address!!.substringBeforeLast(':')
        val port = address.substringAfterLast(':').toInt()
        transport = UsbAdbTransport(
            openChannel = { HeaderLoggingChannel(TcpPacketChannel(host, port)) },
            identity = persistentIdentity(),
            onAwaitingAuthorisation = {
                awaitingAuthorisation.incrementAndGet()
                println("[$ENV] WAITING for the device to authorise this key: accept the 'Allow USB debugging?' dialog (up to 60 s)")
            },
        )
        client = AdbClient(transport)
    }

    @AfterAll
    fun tearDown() {
        if (address != null) transport.close()
    }

    @Test
    @Order(1)
    fun `handshake and authorisation`() = runBlocking {
        val connection = withTimeout(60_000) { transport.connect() }
        println("[$ENV] banner: ${connection.peerBanner}")
        println("[$ENV] max payload: ${connection.peerMaxPayload}")
        assertTrue(connection.isAlive)
        assertTrue(connection.peerBanner.startsWith("device:"), connection.peerBanner)
    }

    @Test
    @Order(2)
    fun `shell output, failure text and exit code`() = runBlocking {
        assertEquals(AdbResult.Success("hello\n"), client.shell("echo hello").let {
            (it as AdbResult.Success).copy(output = it.output.replace("\r\n", "\n"))
        })
        val failed = client.shell("ls /adbhelper-no-such-dir")
        assertTrue(failed is AdbResult.CommandFailed, failed.toString())
        assertTrue((failed as AdbResult.CommandFailed).message.contains("No such file or directory"), failed.message)
        assertEquals(AdbResult.CommandFailed("command exited with code 3"), client.shell("sh -c 'exit 3'"))
    }

    @Test
    @Order(3)
    fun `one MiB pushed and pulled back is identical`() = runBlocking {
        val path = "/data/local/tmp/adbhelper-it.bin"
        val data = ByteArray(1024 * 1024).also { Random(42).nextBytes(it) }
        try {
            client.push(path, data)
            assertArrayEquals(data, client.pull(path))
        } finally {
            client.shell("rm -f $path")
        }
        assertTrue(client.shell("ls $path") is AdbResult.CommandFailed)
    }

    @Test
    @Order(4)
    fun `screencap returns a whole png`() = runBlocking {
        val bytes = client.raw("exec:screencap -p")
        assertTrue(bytes.size > 1000, "only ${bytes.size} bytes")
        assertTrue(isCompletePng(bytes), "not a complete PNG (${bytes.size} bytes)")
    }

    @Test
    @Order(5)
    fun `three long lived streams stay separate`() = runBlocking {
        val a = client.openService("shell:")
        val b = client.openService("shell:")
        val log = client.openService("shell:logcat -v threadtime -T 5")
        try {
            val seenA = StringBuilder()
            val seenB = StringBuilder()
            val seenLog = StringBuilder()
            // The logcat stream starts by replaying its last five lines.
            readUntil(log, seenLog) { it.isNotBlank() }
            a.write("echo tok_A_\$((40+2))\n".toByteArray())
            b.write("echo tok_B_\$((40+3))\n".toByteArray())
            readUntil(a, seenA) { "tok_A_42" in it }
            readUntil(b, seenB) { "tok_B_43" in it }
            b.write("log -t ITTAG marker_\$((7*6))\n".toByteArray())
            a.write("echo tok_A_\$((50+2))\n".toByteArray())
            readUntil(log, seenLog) { "ITTAG" in it && "marker_42" in it }
            readUntil(a, seenA) { "tok_A_52" in it }
            assertFalse("tok_B_" in seenA, seenA.toString())
            assertFalse("tok_A_" in seenB, seenB.toString())
            assertFalse("tok_A_4" in seenLog || "tok_B_" in seenLog, "shell data leaked into logcat")

            // Closing one stream leaves the others working.
            a.close()
            b.write("echo tok_B_\$((60+1))\n".toByteArray())
            readUntil(b, seenB) { "tok_B_61" in it }
            b.write("log -t ITTAG second_\$((8*8))\n".toByteArray())
            readUntil(log, seenLog) { "second_64" in it }
            assertTrue(client.shell("echo still_up") is AdbResult.Success)
        } finally {
            a.close()
            b.close()
            log.close()
        }
    }

    @Test
    @Order(6)
    fun `closing a stream ends a blocked read promptly`() = runBlocking {
        val stream = client.openService("shell:sleep 30")
        val blocked = CoroutineScope(Dispatchers.IO).async { runCatching { stream.readChunk() } }
        Thread.sleep(500)
        val started = System.nanoTime()
        stream.close()
        val result = withTimeout(3_000) { blocked.await() }
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        val error = result.exceptionOrNull()
        assertTrue(error is IOException && error.message == "Stream closed.", "got $result")
        assertTrue(elapsedMillis < 1_000, "took $elapsedMillis ms")
    }

    @Test
    @Order(7)
    fun `invalidate reconnects without a new prompt`() = runBlocking {
        val before = awaitingAuthorisation.get()
        val oldConnection = transport.connect()
        transport.invalidate()
        assertFalse(oldConnection.isAlive)
        assertEquals(AdbResult.Success("again\n"), client.shell("echo again").let {
            (it as AdbResult.Success).copy(output = it.output.replace("\r\n", "\n"))
        })
        assertEquals(before, awaitingAuthorisation.get())
    }

    @Test
    @Order(8)
    fun `twenty shell calls in a row succeed`() = runBlocking {
        repeat(20) { i ->
            val result = client.shell("true")
            assertTrue(result is AdbResult.Success, "call $i: $result")
        }
    }

    @Test
    @Order(9)
    fun `thirty two MiB pushed is verified on the device and pulled back identical`() = runBlocking {
        // The second target is the shared storage (FUSE), a much slower sink than /data.
        for (path in listOf("/data/local/tmp/adbhelper-it-big.bin", "/sdcard/Download/adbhelper-it-big.bin")) bigTransfer(path)
    }

    private suspend fun bigTransfer(path: String) {
        val data = ByteArray(32 * 1024 * 1024).also { Random(7).nextBytes(it) }
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        try {
            val started = System.nanoTime()
            client.push(path, data)
            println("[$ENV] pushed ${data.size} bytes in ${(System.nanoTime() - started) / 1_000_000} ms")
            val size = client.shell("wc -c < $path")
            assertTrue(size is AdbResult.Success && size.output.trim() == data.size.toString(), size.toString())
            val remote = client.shell("sha256sum $path")
            assertTrue(remote is AdbResult.Success && remote.output.startsWith(sha), "device says $remote, expected $sha")
            val back = client.pull(path)
            assertEquals(data.size, back.size)
            assertTrue(data.contentEquals(back), "pulled bytes differ")
        } finally {
            client.shell("rm -f $path")
        }
    }

    @Test
    @Order(10)
    fun `several medium transfers in a row`() = runBlocking {
        val path = "/data/local/tmp/adbhelper-it-medium.bin"
        try {
            repeat(8) { i ->
                val data = ByteArray(6 * 1024 * 1024 + i * 12_345).also { Random(100L + i).nextBytes(it) }
                client.push(path, data)
                val size = client.shell("wc -c < $path")
                assertTrue(size is AdbResult.Success && size.output.trim() == data.size.toString(), "round $i: $size")
                assertTrue(data.contentEquals(client.pull(path)), "round $i: pulled bytes differ")
            }
        } finally {
            client.shell("rm -f $path")
        }
    }

    private suspend fun readUntil(stream: AdbStream, into: StringBuilder, done: (String) -> Boolean) {
        withTimeout(15_000) {
            while (!done(into.toString())) {
                val chunk = stream.readChunk() ?: throw IOException("stream ended; got: $into")
                into.append(String(chunk, Charsets.UTF_8))
            }
        }
    }

    /** Prints the first packet headers the device sends of each connection, to see what a real
     * adbd opens with (version, max payload, AUTH sequence, checksums). */
    private class HeaderLoggingChannel(private val inner: AdbPacketChannel) : AdbPacketChannel by inner {
        private var logged = 0

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = inner.read(buffer, offset, length)
            if (count == AdbWire.HEADER_LENGTH && length == AdbWire.HEADER_LENGTH && logged < 4) {
                logged++
                val words = java.nio.ByteBuffer.wrap(buffer, offset, count).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                val command = words.int
                println(
                    "[$ENV] device header: ${AdbMessage.commandName(command)} arg0=0x%x arg1=0x%x len=%d checksum=0x%x"
                        .format(words.int, words.int, words.int, words.int),
                )
            }
            return count
        }
    }

    private companion object {
        const val ENV = "ADBHELPER_REAL_ADBD"

        /** An RSA-2048 key kept under the user's home (never in the repo), made on first use. */
        fun persistentIdentity(): RsaAdbIdentity {
            val dir = File(System.getProperty("user.home"), ".adbhelper-test").apply { mkdirs() }
            val file = File(dir, "adbkey.pk8")
            if (!file.exists()) {
                val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
                file.writeBytes(pair.private.encoded)
            }
            val factory = KeyFactory.getInstance("RSA")
            val private = factory.generatePrivate(PKCS8EncodedKeySpec(file.readBytes())) as RSAPrivateCrtKey
            val public = factory.generatePublic(RSAPublicKeySpec(private.modulus, private.publicExponent))
            return RsaAdbIdentity(private, public as java.security.interfaces.RSAPublicKey, "adbhelper-test@host")
        }
    }
}

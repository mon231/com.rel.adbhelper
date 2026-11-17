package com.rel.adbhelper.core.session

import com.rel.adbhelper.core.adb.AdbClient
import com.rel.adbhelper.core.adb.FakeAdbTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.IOException

class ConnectionHealthTest {
    private fun client(transport: FakeAdbTransport) = AdbClient(transport, watchdogDispatcher = Dispatchers.Unconfined, log = {})

    @Test
    fun `failure marks lost and success clears it`() {
        val h = ConnectionHealth()
        assertNull(h.lost.value)
        val e = IOException("gone")
        h.reportFailure(e)
        assertEquals(e, h.lost.value)
        h.reportSuccess()
        assertNull(h.lost.value)
    }

    @Test
    fun `unreachable shell marks lost, rejected command does not`() = runTest {
        val down = client(FakeAdbTransport(throwOnOpen = IOException("refused")))
        down.shell("echo ok")
        assertNotNull(down.health.lost.value)

        val rejecting = client(FakeAdbTransport(exitCode = 1))
        rejecting.shell("false")
        assertNull(rejecting.health.lost.value)
    }

    @Test
    fun `stream read error marks lost but own close does not`() = runTest {
        val broken = client(FakeAdbTransport(serviceOutputs = mapOf("s" to ByteArray(1)), throwOnServiceRead = IOException("reset")))
        runCatching { broken.openService("s").readChunk() }
        assertNotNull(broken.health.lost.value)

        val closed = client(FakeAdbTransport(serviceOutputs = mapOf("s" to ByteArray(1)), throwOnServiceRead = IOException("reset")))
        val stream = closed.openService("s")
        stream.close()
        runCatching { stream.readChunk() }
        assertNull(closed.health.lost.value)
    }

    @Test
    fun `later success clears banner state`() = runTest {
        val c = client(FakeAdbTransport(scriptedOutput = "ok\n"))
        c.health.reportFailure(IOException("old"))
        c.shell("echo ok")
        assertNull(c.health.lost.value)
    }
}

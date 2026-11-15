package com.rel.adbhelper.features.mirror

import com.rel.adbhelper.core.adb.AdbClient
import com.rel.adbhelper.core.scrcpy.ControlMessages
import com.rel.adbhelper.core.scrcpy.QueueStream
import com.rel.adbhelper.core.scrcpy.ScriptedTransport
import com.rel.adbhelper.core.scrcpy.ScrcpyLink
import com.rel.adbhelper.core.scrcpy.ScrcpySession
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

private class RecordingSink : VideoSink {
    val events = CopyOnWriteArrayList<String>()
    override fun onSession(width: Int, height: Int) { events.add("session ${width}x$height") }
    override fun onConfig(data: ByteArray) { events.add("config ${data.size}") }
    override fun onMedia(data: ByteArray, ptsMicros: Long, keyFrame: Boolean) { events.add("media $ptsMicros $keyFrame") }
}

class MirrorViewModelTest {
    private val scid = 0x77
    private val jar = ByteArray(10)
    private var viewModel: MirrorViewModel? = null

    @AfterEach
    fun tearDown() {
        viewModel?.close()
    }

    private fun transport(refuse: Int = 0) = ScriptedTransport(
        scid, jar.size.toLong(), QueueStream(), QueueStream(byteArrayOf(0)), QueueStream(), refuse,
    )

    private fun viewModel(t: ScriptedTransport, sink: VideoSink? = null): MirrorViewModel = MirrorViewModel(
        sessionFactory = {
            ScrcpySession(
                openLink = { ScrcpyLink(AdbClient(t)) },
                loadServerJar = { jar },
                scid = scid,
                connectAttempts = 4,
                retryDelayMillis = 30,
            )
        },
        feedDispatcher = Dispatchers.IO,
    ).also {
        it.setSink(sink)
        viewModel = it
    }

    private fun awaitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertTrue(condition(), "timed out waiting for: $what")
    }

    private val session720 = byteArrayOf(0x80.toByte(), 0, 0, 0, 0, 0, 0x02, 0xD0.toByte(), 0, 0, 0x05, 0)
    private val config = byteArrayOf(0x40, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2, 0x67, 0x68)
    private val keyFrame = byteArrayOf(0x20, 0, 0, 0, 0, 0, 0, 5, 0, 0, 0, 1, 0x65)

    @Test
    fun `running state carries the video size and packets reach the sink in order`() {
        val t = transport()
        val sink = RecordingSink()
        val vm = viewModel(t, sink)
        assertEquals(MirrorViewModel.State.Connecting, vm.state.value)
        vm.start()
        awaitUntil("running") { vm.state.value is MirrorViewModel.State.Running }
        t.video.feed(session720 + config + keyFrame)
        awaitUntil("media") { sink.events.size == 3 }
        assertEquals(listOf("session 720x1280", "config 2", "media 5 true"), sink.events.toList())
        assertEquals(MirrorViewModel.State.Running("Test phone", VideoSize(720, 1280)), vm.state.value)
    }

    @Test
    fun `a server that never opens its socket becomes an error with the server's message`() {
        val t = transport(refuse = 100)
        t.server.feed("java.lang.IllegalStateException: no encoder\n".toByteArray())
        t.server.endOfStream()
        val vm = viewModel(t)
        vm.start()
        awaitUntil("error") { vm.state.value is MirrorViewModel.State.Error }
        val message = (vm.state.value as MirrorViewModel.State.Error).message
        assertTrue(message.contains("no encoder"), message)
    }

    @Test
    fun `the video stream ending after start is an error, not a silent freeze`() {
        val t = transport()
        val vm = viewModel(t)
        vm.start()
        awaitUntil("running") { vm.state.value is MirrorViewModel.State.Running }
        t.video.endOfStream()
        awaitUntil("error") { vm.state.value is MirrorViewModel.State.Error }
    }

    @Test
    fun `touch buttons and keys become control messages using the current video size`() {
        val t = transport()
        val vm = viewModel(t)
        vm.start()
        awaitUntil("running") { vm.state.value is MirrorViewModel.State.Running }
        t.video.feed(session720)
        awaitUntil("size") { (vm.state.value as? MirrorViewModel.State.Running)?.videoSize != null }

        // View is half the video size: (360, 640) maps to (720, 1280)/... use exact corners.
        vm.touch(ControlMessages.ACTION_DOWN, 0, 180f, 320f, 360, 640)
        vm.touch(ControlMessages.ACTION_MOVE, 0, 181f, 321f, 360, 640)
        vm.touch(ControlMessages.ACTION_MOVE, 7, 10f, 10f, 360, 640) // never went down: dropped
        vm.touch(ControlMessages.ACTION_UP, 0, 181f, 321f, 360, 640)
        vm.pressBack()
        vm.pressHome()
        vm.pressRecents()
        awaitUntil("control writes") { t.control.writtenBytes().size == 9 }
        val sent = t.control.writtenBytes()

        assertArrayEquals(ControlMessages.injectTouch(0, 0, 360, 640, 720, 1280, 1f), sent[0])
        assertArrayEquals(ControlMessages.injectTouch(2, 0, 362, 642, 720, 1280, 1f), sent[1])
        assertArrayEquals(ControlMessages.injectTouch(1, 0, 362, 642, 720, 1280, 0f), sent[2])
        assertArrayEquals(byteArrayOf(4, 0), sent[3])
        assertArrayEquals(byteArrayOf(4, 1), sent[4])
        assertArrayEquals(ControlMessages.injectKeycode(0, 3), sent[5])
        assertArrayEquals(ControlMessages.injectKeycode(1, 3), sent[6])
        assertArrayEquals(ControlMessages.injectKeycode(0, 187), sent[7])
        assertArrayEquals(ControlMessages.injectKeycode(1, 187), sent[8])
    }

    @Test
    fun `touches before the video size is known are dropped`() {
        val t = transport()
        val vm = viewModel(t)
        vm.start()
        awaitUntil("running") { vm.state.value is MirrorViewModel.State.Running }
        vm.touch(ControlMessages.ACTION_DOWN, 0, 1f, 1f, 100, 100)
        vm.pressBack()
        awaitUntil("back") { t.control.writtenBytes().size == 2 }
        assertArrayEquals(byteArrayOf(4, 0), t.control.writtenBytes()[0])
    }

    @Test
    fun `close closes the server video and control streams`() {
        val t = transport()
        val vm = viewModel(t)
        vm.start()
        awaitUntil("running") { vm.state.value is MirrorViewModel.State.Running }
        vm.close()
        awaitUntil("streams closed") {
            t.server.closeCount.get() == 1 && t.video.closeCount.get() == 1 && t.control.closeCount.get() == 1
        }
        // Ending the pump because of our own close is not an error.
        Thread.sleep(50)
        assertTrue(vm.state.value is MirrorViewModel.State.Running)
    }
}

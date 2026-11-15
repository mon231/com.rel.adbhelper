package com.rel.adbhelper.core.scrcpy

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * Client-to-server control messages (big endian), layouts from `ControlMessageReader` and the
 * byte vectors in `app/tests/test_control_msg_serialize.c` at scrcpy v5.0.1.
 */
object ControlMessages {
    private const val TYPE_INJECT_KEYCODE = 0
    private const val TYPE_INJECT_TOUCH_EVENT = 2
    private const val TYPE_BACK_OR_SCREEN_ON = 4
    private const val TYPE_RESET_VIDEO = 17

    // android.view.MotionEvent / KeyEvent values, written on the wire as plain integers.
    const val ACTION_DOWN = 0
    const val ACTION_UP = 1
    const val ACTION_MOVE = 2

    const val KEYCODE_HOME = 3
    const val KEYCODE_BACK = 4
    const val KEYCODE_APP_SWITCH = 187

    /** 32 bytes: type, action, pointer id (u64), x, y (i32), screen w, h (u16), pressure (u16 fixed point), two button ints. */
    fun injectTouch(
        action: Int,
        pointerId: Long,
        x: Int,
        y: Int,
        screenWidth: Int,
        screenHeight: Int,
        pressure: Float,
        actionButton: Int = 0,
        buttons: Int = 0,
    ): ByteArray = build {
        writeByte(TYPE_INJECT_TOUCH_EVENT)
        writeByte(action)
        writeLong(pointerId)
        writeInt(x)
        writeInt(y)
        writeShort(screenWidth)
        writeShort(screenHeight)
        writeShort(pressureToU16(pressure))
        writeInt(actionButton)
        writeInt(buttons)
    }

    /** 14 bytes: type, action, keycode, repeat, meta state. */
    fun injectKeycode(action: Int, keycode: Int, repeat: Int = 0, metaState: Int = 0): ByteArray = build {
        writeByte(TYPE_INJECT_KEYCODE)
        writeByte(action)
        writeInt(keycode)
        writeInt(repeat)
        writeInt(metaState)
    }

    /** 2 bytes. Presses Back, or wakes the screen if it is off. [action] is [ACTION_DOWN] or [ACTION_UP]. */
    fun backOrScreenOn(action: Int): ByteArray = byteArrayOf(TYPE_BACK_OR_SCREEN_ON.toByte(), action.toByte())

    /** 1 byte. Restarts the capture, which makes the server send a fresh config packet and key frame. */
    fun resetVideo(): ByteArray = byteArrayOf(TYPE_RESET_VIDEO.toByte())

    /** `sc_float_to_u16fp`: 1.0 maps to 0xffff, everything else to value * 2^16. */
    fun pressureToU16(pressure: Float): Int {
        val v = (pressure.coerceIn(0f, 1f) * 65536f).toLong()
        return if (v >= 0xffff) 0xffff else v.toInt()
    }

    private inline fun build(block: DataOutputStream.() -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream(32)
        DataOutputStream(bytes).block()
        return bytes.toByteArray()
    }
}

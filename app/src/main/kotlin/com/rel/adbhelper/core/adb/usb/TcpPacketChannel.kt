package com.rel.adbhelper.core.adb.usb

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * [AdbPacketChannel] over a plain TCP socket to an adbd (the classic port 5555 mode, which speaks
 * the same protocol as USB). Used to run the whole USB stack against an emulator or a real
 * `adbd` without USB hardware.
 *
 * Reads time out after [READ_TIMEOUT_MILLIS] and then return 0, as the channel contract allows.
 * [close] closes the socket, from any thread, which ends a read or write blocked on another one.
 */
class TcpPacketChannel(host: String, port: Int) : AdbPacketChannel {

    private val socket = Socket()
    private val input: InputStream
    private val output: OutputStream

    @Volatile
    private var closed = false

    init {
        try {
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS)
            socket.tcpNoDelay = true
            socket.soTimeout = READ_TIMEOUT_MILLIS
            input = socket.getInputStream()
            output = socket.getOutputStream()
        } catch (e: IOException) {
            socket.close()
            throw e
        }
    }

    override fun write(bytes: ByteArray) {
        ensureOpen()
        try {
            output.write(bytes)
            output.flush()
        } catch (e: IOException) {
            throw if (closed) IOException(CLOSED, e) else e
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        ensureOpen()
        if (length <= 0) return 0
        try {
            val count = input.read(buffer, offset, length)
            if (count < 0) throw IOException("The connection was closed by the peer")
            return count
        } catch (_: SocketTimeoutException) {
            ensureOpen()
            return 0
        } catch (e: IOException) {
            throw if (closed) IOException(CLOSED, e) else e
        }
    }

    override fun close() {
        closed = true
        runCatching { socket.close() }
    }

    private fun ensureOpen() {
        if (closed) throw IOException(CLOSED)
    }

    private companion object {
        const val READ_TIMEOUT_MILLIS = 1_000
        const val CONNECT_TIMEOUT_MILLIS = 5_000
        const val CLOSED = "TCP channel closed"
    }
}

package com.rel.adbhelper.core.scrcpy

import com.rel.adbhelper.core.adb.AdbStream
import java.io.IOException

/**
 * Buffered reader over a chunked byte source. Stream chunks arrive at arbitrary boundaries (a
 * 12-byte header can be split across chunks, one chunk can hold several packets), so every parser
 * reads through [readExactly] rather than assuming chunk alignment.
 */
class ByteStreamReader(private val source: suspend () -> ByteArray?) {
    constructor(stream: AdbStream) : this({ stream.readChunk() })

    private var chunk = ByteArray(0)
    private var position = 0

    /** Exactly [count] bytes; [IOException] if the stream ends first. */
    suspend fun readExactly(count: Int): ByteArray {
        require(count >= 0) { "count must not be negative" }
        val out = ByteArray(count)
        var filled = 0
        while (filled < count) {
            if (position >= chunk.size) {
                chunk = source() ?: throw IOException("Stream ended after $filled of $count bytes")
                position = 0
                continue
            }
            val n = minOf(count - filled, chunk.size - position)
            System.arraycopy(chunk, position, out, filled, n)
            position += n
            filled += n
        }
        return out
    }
}

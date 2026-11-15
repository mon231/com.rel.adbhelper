package com.rel.adbhelper.core.scrcpy

import java.io.IOException

/**
 * scrcpy wire formats, pinned to release v5.0.1 (see README.md in this package for the sources).
 * The server rejects any other client version string, so [VERSION] and the bundled
 * `scrcpy-server.jar` must always change together.
 */
object ScrcpyProtocol {
    const val VERSION = "5.0.1"
    const val SERVER_PATH = "/data/local/tmp/scrcpy-server.jar"
    const val ASSET_NAME = "scrcpy-server.jar"

    /** Video codec id for "h264" (develop.md: `0x68323634`). */
    const val CODEC_H264 = 0x68323634

    /** Device name field on the first socket: fixed 64 bytes, NUL padded (DesktopConnection). */
    const val DEVICE_NAME_LENGTH = 64

    /** Both the session packet and the media-packet header are 12 bytes. */
    const val HEADER_LENGTH = 12

    private const val FLAG_SESSION = 0x80
    private const val FLAG_CONFIG = 0x40
    private const val FLAG_KEY_FRAME = 0x20
    private const val MAX_PACKET_SIZE = 16 * 1024 * 1024

    /** Server's abstract socket name for [scid] (`scrcpy_%08x`, from DesktopConnection). */
    fun socketName(scid: Int): String = "scrcpy_%08x".format(scid)

    /** adb service string that opens the server's socket for [scid]. */
    fun socketService(scid: Int): String = "localabstract:${socketName(scid)}"

    /**
     * The shell command that starts the server. Video only (audio, clipboard sync off), a forward
     * tunnel (the server listens, we connect), and `cleanup=true` so the server deletes its jar on
     * exit. [scid] is passed as hex, which is how the server parses it.
     */
    fun serverCommand(scid: Int, maxSize: Int = 1280, videoBitRate: Int = 4_000_000): String = listOf(
        "CLASSPATH=$SERVER_PATH app_process / com.genymobile.scrcpy.Server $VERSION",
        "scid=%08x".format(scid),
        "log_level=info",
        "tunnel_forward=true",
        "video=true",
        "audio=false",
        "control=true",
        "cleanup=true",
        "clipboard_autosync=false",
        "video_codec=h264",
        "max_size=$maxSize",
        "video_bit_rate=$videoBitRate",
    ).joinToString(" ")

    /** Device name and codec id, read from the video socket once the control socket is open. */
    data class StreamInfo(val deviceName: String, val codecId: Int)

    /** The single dummy byte a forward tunnel sends first; reading it proves the server accepted. */
    suspend fun readDummyByte(reader: ByteStreamReader) {
        reader.readExactly(1)
    }

    /** 64-byte device name followed by the u32 codec id. Anything but H.264 is an error. */
    suspend fun readStreamInfo(reader: ByteStreamReader): StreamInfo {
        val nameBytes = reader.readExactly(DEVICE_NAME_LENGTH)
        val end = nameBytes.indexOf(0).let { if (it < 0) nameBytes.size else it }
        val codecId = u32(reader.readExactly(4), 0).toInt()
        // Streamer.writeDisableStream: codec id 0 = stream disabled, 1 = configuration error.
        if (codecId == 0 || codecId == 1) throw IOException("The server could not start the video stream")
        if (codecId != CODEC_H264) throw IOException("Unsupported video codec 0x%08x".format(codecId))
        return StreamInfo(String(nameBytes, 0, end, Charsets.UTF_8), codecId)
    }

    /** One item of the video socket after the preamble. */
    sealed interface VideoPacket {
        /** Start of a capture session; sent again after rotation or a reset. */
        data class Session(val width: Int, val height: Int, val clientResized: Boolean) : VideoPacket

        /** Codec configuration (SPS/PPS in Annex B form for H.264). */
        class Config(val data: ByteArray) : VideoPacket

        class Media(val ptsMicros: Long, val keyFrame: Boolean, val data: ByteArray) : VideoPacket
    }

    suspend fun readVideoPacket(reader: ByteStreamReader): VideoPacket {
        val header = reader.readExactly(HEADER_LENGTH)
        val first = header[0].toInt() and 0xFF
        if (first and FLAG_SESSION != 0) {
            val width = u32(header, 4).toInt()
            val height = u32(header, 8).toInt()
            if (width <= 0 || height <= 0) throw IOException("Invalid video size ${width}x$height")
            return VideoPacket.Session(width, height, clientResized = header[3].toInt() and 1 != 0)
        }
        val size = u32(header, 8)
        if (size > MAX_PACKET_SIZE) throw IOException("Video packet too large: $size bytes")
        val payload = reader.readExactly(size.toInt())
        if (first and FLAG_CONFIG != 0) return VideoPacket.Config(payload)
        var pts = 0L
        for (i in 0 until 8) pts = (pts shl 8) or (header[i].toLong() and 0xFF)
        pts = pts and ((1L shl 61) - 1)
        return VideoPacket.Media(pts, first and FLAG_KEY_FRAME != 0, payload)
    }

    private fun u32(b: ByteArray, offset: Int): Long =
        ((b[offset].toLong() and 0xFF) shl 24) or ((b[offset + 1].toLong() and 0xFF) shl 16) or
            ((b[offset + 2].toLong() and 0xFF) shl 8) or (b[offset + 3].toLong() and 0xFF)
}

package com.jarvis.assistant.tts

import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavParser {
    /** Parses a PCM16 RIFF/WAVE file. Throws [TtsException] for anything else. */
    fun parse(bytes: ByteArray): PcmAudio {
        if (bytes.size < 44 || String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" ||
            String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE"
        ) {
            throw TtsException(TtsError.ENGINE, "Not a WAV file")
        }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var sampleRate = 0
        var channels = 0
        var bits = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val declared = buf.getInt(pos + 4).toLong() and 0xFFFFFFFFL
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    channels = buf.getShort(body + 2).toInt()
                    sampleRate = buf.getInt(body + 4)
                    bits = buf.getShort(body + 14).toInt()
                }
                "data" -> {
                    if (bits != 16 || channels !in 1..2 || sampleRate <= 0) {
                        throw TtsException(TtsError.ENGINE, "Unsupported WAV format")
                    }
                    // Streaming encoders may write 0 / 0xFFFFFFFF; trust the file length then.
                    val available = (bytes.size - body).toLong()
                    val len = if (declared == 0L || declared > available) available else declared
                    val usable = (len - len % (2L * channels)).toInt()
                    return PcmAudio(bytes.copyOfRange(body, body + usable), sampleRate, channels)
                }
            }
            pos = body + declared.toInt().coerceAtLeast(0) + (declared.toInt() and 1)
            if (declared > Int.MAX_VALUE) break
        }
        throw TtsException(TtsError.ENGINE, "WAV has no audio data")
    }
}

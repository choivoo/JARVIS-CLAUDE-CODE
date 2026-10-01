package com.jarvis.assistant

import com.jarvis.assistant.tts.JarvisSpeaker
import com.jarvis.assistant.tts.TtsException
import com.jarvis.assistant.tts.WavParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavParserTest {
    private fun wav(samples: ShortArray, rate: Int, channels: Int = 1, dataSizeOverride: Int? = null): ByteArray {
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { data.putShort(it) }
        val out = ByteBuffer.allocate(44 + data.capacity()).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray()).putInt(36 + data.capacity()).put("WAVE".toByteArray())
        out.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort()).putInt(rate)
            .putInt(rate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
        out.put("data".toByteArray()).putInt(dataSizeOverride ?: data.capacity()).put(data.array())
        return out.array()
    }

    @Test
    fun parsesMono16() {
        val audio = WavParser.parse(wav(shortArrayOf(1, 2, 3, 4), 22050))
        assertEquals(22050, audio.sampleRate)
        assertEquals(1, audio.channels)
        assertEquals(4, audio.frames)
    }

    @Test
    fun toleratesStreamingSizes() {
        assertEquals(3, WavParser.parse(wav(shortArrayOf(1, 2, 3), 16000, dataSizeOverride = -1)).frames)
        assertEquals(3, WavParser.parse(wav(shortArrayOf(1, 2, 3), 16000, dataSizeOverride = 0)).frames)
    }

    @Test(expected = TtsException::class)
    fun rejectsGarbage() {
        WavParser.parse(ByteArray(100))
    }

    @Test
    fun splitsSentences() {
        val chunks = JarvisSpeaker.splitSentences("Certainly. I'll check the weather for you. One moment.", maxLen = 30)
        assertTrue(chunks.size >= 2)
        assertTrue(chunks.all { it.length <= 30 })
    }
}

package com.jarvis.assistant.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.jarvis.assistant.util.AudioLevelBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * Plays [PcmAudio] and publishes its real amplitude envelope (RMS per ~20 ms window, aligned to the
 * playback head) to the [AudioLevelBus] so the HUD waveform follows what is actually audible.
 */
class PcmPlayer(private val levels: AudioLevelBus) {

    suspend fun play(audio: PcmAudio) {
        if (audio.data.isEmpty()) return
        val channelMask = if (audio.channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBuffer = AudioTrack.getMinBufferSize(audio.sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(audio.sampleRate)
                    .setChannelMask(channelMask)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuffer * 2, audio.sampleRate / 5 * 2 * audio.channels))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        val hop = (audio.sampleRate / 50).coerceAtLeast(1) // 20 ms of frames
        val envelope = envelope(audio, hop)
        val totalFrames = audio.frames

        try {
            track.play()
            coroutineScope {
                val writer = launch(Dispatchers.IO) {
                    var offset = 0
                    val chunk = 4096
                    while (offset < audio.data.size && isActive) {
                        val n = minOf(chunk, audio.data.size - offset)
                        val written = track.write(audio.data, offset, n)
                        if (written < 0) break
                        offset += written
                    }
                }
                val ticker = launch {
                    while (isActive) {
                        val index = (track.playbackHeadPosition / hop).coerceIn(0, envelope.size - 1)
                        levels.set(envelope[index])
                        delay(16)
                    }
                }
                writer.join()
                // Let the buffered tail drain before stopping.
                val deadline = System.nanoTime() + 3_000_000_000L
                while (isActive && track.playbackHeadPosition < totalFrames && System.nanoTime() < deadline) {
                    delay(20)
                }
                ticker.cancel()
            }
        } finally {
            withContext(NonCancellable) {
                try {
                    track.pause()
                    track.flush()
                    track.stop()
                } catch (_: IllegalStateException) {
                }
                track.release()
                levels.set(0f)
            }
        }
    }

    private fun envelope(audio: PcmAudio, hop: Int): FloatArray {
        val frames = audio.frames
        val windows = (frames / hop) + 1
        val result = FloatArray(windows)
        val data = audio.data
        var smoothed = 0f
        for (w in 0 until windows) {
            val startFrame = w * hop
            val endFrame = minOf(frames, startFrame + hop)
            var sum = 0.0
            var count = 0
            for (f in startFrame until endFrame) {
                val i = f * audio.channels * 2
                val sample = ((data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xFF)).toShort().toInt()
                sum += sample.toDouble() * sample
                count++
            }
            val rms = if (count == 0) 0f else (sqrt(sum / count) / 32768.0).toFloat()
            val target = (rms * 3.2f).coerceIn(0f, 1f)
            // Fast attack, slower release: reads as natural speech movement.
            smoothed = if (target > smoothed) target else smoothed * 0.8f + target * 0.2f
            result[w] = smoothed
        }
        return result
    }
}

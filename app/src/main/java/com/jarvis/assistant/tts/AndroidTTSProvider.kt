package com.jarvis.assistant.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.jarvis.assistant.data.model.TtsProviderType
import com.jarvis.assistant.util.JLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume

data class VoiceOption(val id: String, val label: String)

/**
 * Offline-capable default voice. Audio is rendered to a WAV file and played by [PcmPlayer], which
 * lets the HUD follow the real amplitude. The JARVIS voice profile is: English, male, British
 * where available, low pitch, slow and composed.
 */
class AndroidTTSProvider(private val context: Context) : TTSProvider {
    override val type = TtsProviderType.ANDROID

    private val initMutex = Mutex()
    private val synthMutex = Mutex()
    private var tts: TextToSpeech? = null

    private suspend fun engine(): TextToSpeech = initMutex.withLock {
        tts?.let { return it }
        val ready = CompletableDeferred<Boolean>()
        val created = withContext(Dispatchers.Main) {
            TextToSpeech(context.applicationContext) { status -> ready.complete(status == TextToSpeech.SUCCESS) }
        }
        val ok = try {
            withTimeout(8_000) { ready.await() }
        } catch (e: Exception) {
            false
        }
        if (!ok) {
            created.shutdown()
            throw TtsException(TtsError.ENGINE, "Text-to-speech engine unavailable")
        }
        tts = created
        created
    }

    /** English voices installed on the device, best JARVIS candidates first. */
    suspend fun listVoices(): List<VoiceOption> = try {
        val engine = engine()
        (engine.voices ?: emptySet())
            .filter { it.locale.language == "en" }
            .sortedByDescending { score(it) }
            .map { VoiceOption(it.name, "${it.name}  (${it.locale.displayCountry.ifEmpty { it.locale.language }})") }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    override suspend fun synthesize(text: String, settings: VoiceSettings): PcmAudio {
        val engine = engine()
        return synthMutex.withLock {
            configure(engine, settings)
            val file = File(context.cacheDir, "tts_${UUID.randomUUID()}.wav")
            try {
                renderToFile(engine, text, file)
                WavParser.parse(file.readBytes())
            } finally {
                file.delete()
            }
        }
    }

    /** Last-resort path when file synthesis fails: the engine plays directly (no waveform data). */
    suspend fun speakDirect(text: String, settings: VoiceSettings) {
        val engine = engine()
        synthMutex.withLock {
            configure(engine, settings)
            suspendCancellableCoroutine { cont ->
                val id = UUID.randomUUID().toString()
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        if (cont.isActive) cont.resume(Unit)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        if (cont.isActive) cont.resume(Unit)
                    }
                })
                cont.invokeOnCancellation { engine.stop() }
                val rc = engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
                if (rc != TextToSpeech.SUCCESS && cont.isActive) cont.resume(Unit)
            }
        }
    }

    private fun configure(engine: TextToSpeech, settings: VoiceSettings) {
        val locale = Locale.UK
        val langResult = engine.setLanguage(locale)
        if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.setLanguage(Locale.US)
        }
        val voices = engine.voices.orEmpty().filter { it.locale.language == "en" }
        val chosen = voices.firstOrNull { it.name == settings.voice } ?: voices.maxByOrNull { score(it) }
        if (chosen != null) {
            try {
                engine.voice = chosen
            } catch (e: Exception) {
                JLog.w("TTS", "Could not select voice", e)
            }
        }
        engine.setSpeechRate(settings.rate)
        engine.setPitch(settings.pitch)
    }

    private suspend fun renderToFile(engine: TextToSpeech, text: String, file: File) {
        withTimeout(30_000) {
            suspendCancellableCoroutine<Unit> { cont ->
                val id = UUID.randomUUID().toString()
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        if (cont.isActive) cont.resume(Unit)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        if (cont.isActive) {
                            cont.resumeWith(Result.failure(TtsException(TtsError.ENGINE, "Synthesis failed")))
                        }
                    }
                })
                cont.invokeOnCancellation { engine.stop() }
                val rc = engine.synthesizeToFile(text, Bundle(), file, id)
                if (rc != TextToSpeech.SUCCESS && cont.isActive) {
                    cont.resumeWith(Result.failure(TtsException(TtsError.ENGINE, "Synthesis rejected")))
                }
            }
        }
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
    }

    companion object {
        // Google TTS en-GB variants that are usually male (gbb, gbd, rjs), e.g. "en-gb-x-gbd-local".
        private val maleMarkers = listOf("-gbb-", "-gbd-", "-rjs-", "male")

        internal fun score(voice: Voice): Int {
            val name = voice.name.lowercase(Locale.ROOT)
            var s = 0
            if (voice.locale.country.equals("GB", ignoreCase = true)) s += 6
            else if (voice.locale.country.equals("AU", ignoreCase = true) ||
                voice.locale.country.equals("IE", ignoreCase = true)
            ) s += 2
            if (maleMarkers.any { name.contains(it) } && !name.contains("female")) s += 5
            if (name.contains("female")) s -= 5
            if (!voice.isNetworkConnectionRequired) s += 1
            s += voice.quality / 100
            return s
        }
    }
}

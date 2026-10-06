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
        // Prefer Google's engine: it ships the en-GB male voices. Fall back to the device default.
        val googleInstalled = try {
            context.packageManager.getPackageInfo(GOOGLE_TTS, 0); true
        } catch (e: Exception) {
            false
        }
        var created = withContext(Dispatchers.Main) {
            if (googleInstalled) {
                TextToSpeech(context.applicationContext, { status -> ready.complete(status == TextToSpeech.SUCCESS) }, GOOGLE_TTS)
            } else {
                TextToSpeech(context.applicationContext) { status -> ready.complete(status == TextToSpeech.SUCCESS) }
            }
        }
        if (googleInstalled && !(try { withTimeout(8_000) { ready.await() } } catch (e: Exception) { false })) {
            created.shutdown()
            val retry = CompletableDeferred<Boolean>()
            created = withContext(Dispatchers.Main) {
                TextToSpeech(context.applicationContext) { status -> retry.complete(status == TextToSpeech.SUCCESS) }
            }
            val ok2 = try { withTimeout(8_000) { retry.await() } } catch (e: Exception) { false }
            if (!ok2) {
                created.shutdown()
                throw TtsException(TtsError.ENGINE, "Text-to-speech engine unavailable")
            }
            tts = created
            return created
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
    suspend fun listVoices(maleOnly: Boolean = false): List<VoiceOption> = try {
        val engine = engine()
        (engine.voices ?: emptySet())
            .filter { it.locale.language == "en" }
            .filter { !maleOnly || gender(it) != Gender.FEMALE }
            .sortedByDescending { score(it) }
            .map {
                val g = when (gender(it)) { Gender.MALE -> "♂ "; Gender.FEMALE -> "♀ "; Gender.UNKNOWN -> "" }
                VoiceOption(it.name, "$g${it.name}  (${it.locale.displayCountry.ifEmpty { it.locale.language }})")
            }
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
        val all = engine.voices.orEmpty().filter { it.locale.language == "en" }
        // "Male only": never auto-pick a known female voice while any other voice is available.
        val voices = if (settings.maleOnly) all.filter { gender(it) != Gender.FEMALE }.ifEmpty { all } else all
        val chosen = all.firstOrNull { it.name == settings.voice } ?: voices.maxByOrNull { score(it) }
        var pitch = settings.pitch
        if (chosen != null) {
            // No known-male voice installed: lower the pitch so an unknown or female voice sounds less feminine.
            if (settings.voice.isBlank() || chosen.name != settings.voice) {
                if (gender(chosen) != Gender.MALE) pitch = (pitch * 0.82f).coerceAtLeast(0.5f)
            }
            try {
                engine.voice = chosen
            } catch (e: Exception) {
                JLog.w("TTS", "Could not select voice", e)
            }
        }
        engine.setSpeechRate(settings.rate)
        engine.setPitch(pitch)
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

    enum class Gender { MALE, FEMALE, UNKNOWN }

    companion object {
        // Google TTS variant codes. en-GB: gbb, gbd, rjs are male; gba, gbc, gbg, gbf female.
        // en-US: iol, iom, tpd, sfg(f) ... only the commonly known ones are listed.
        private const val GOOGLE_TTS = "com.google.android.tts"
        private val maleMarkers = listOf(
            "-gbb-", "-gbd-", "-rjs-", "-iol-", "-iom-", "-tpd-", "-ause-", "-aub-", "-aud-", "-ieh-",
            "smtm", "male", "_m_", "-m-",
        )
        private val femaleMarkers = listOf(
            "female", "-gba-", "-gbc-", "-gbf-", "-gbg-", "-iob-", "-iog-", "-tpc-", "-tpf-", "-sfg-",
            "-aua-", "-auc-", "-ieg-", "smtf", "_f_", "-f-",
        )

        internal fun gender(voice: Voice): Gender {
            val name = voice.name.lowercase(Locale.ROOT)
            if (femaleMarkers.any { name.contains(it) }) return Gender.FEMALE
            if (maleMarkers.any { name.contains(it) }) return Gender.MALE
            return Gender.UNKNOWN
        }

        internal fun score(voice: Voice): Int {
            var s = 0
            if (voice.locale.country.equals("GB", ignoreCase = true)) s += 6
            else if (voice.locale.country.equals("AU", ignoreCase = true) ||
                voice.locale.country.equals("IE", ignoreCase = true)
            ) s += 2
            s += when (gender(voice)) {
                Gender.MALE -> 30
                Gender.FEMALE -> -30
                Gender.UNKNOWN -> 0
            }
            if (!voice.isNetworkConnectionRequired) s += 1
            s += voice.quality / 100
            return s
        }
    }
}

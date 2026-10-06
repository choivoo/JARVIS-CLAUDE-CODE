package com.friday.assistant.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.friday.assistant.settings.FridaySettings
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/** Offline fallback voice: the system TTS, steered toward an English female-sounding voice. */
class AndroidTTSProvider(
    private val context: Context,
    private val settings: () -> FridaySettings,
) : TTSProvider {
    override val name = "Android TTS"
    private val _amp = MutableStateFlow(0f)
    override val amplitude: StateFlow<Float> = _amp.asStateFlow()
    private var tts: TextToSpeech? = null
    private var ready: CompletableDeferred<Boolean>? = null

    private suspend fun engine(): TextToSpeech {
        tts?.let { if (ready?.await() == true) return it }
        val d = CompletableDeferred<Boolean>()
        ready = d
        tts = TextToSpeech(context.applicationContext) { status -> d.complete(status == TextToSpeech.SUCCESS) }
        if (!withTimeout(5_000) { d.await() }) { tts = null; throw TtsException("System TTS unavailable") }
        return tts!!
    }

    override suspend fun speak(text: String) {
        val s = settings()
        val engine = engine()
        engine.language = Locale.US
        pickVoice(engine, s.androidVoice)?.let { engine.voice = it }
        engine.setSpeechRate(s.ttsSpeed)
        engine.setPitch(s.ttsPitch)
        val id = UUID.randomUUID().toString()
        suspendCancellableCoroutine { cont ->
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { _amp.value = 0.6f }
                override fun onDone(utteranceId: String?) { _amp.value = 0f; if (utteranceId == id && cont.isActive) cont.resume(Unit) }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { _amp.value = 0f; if (utteranceId == id && cont.isActive) cont.resume(Unit) }
                override fun onStop(utteranceId: String?, interrupted: Boolean) { _amp.value = 0f; if (cont.isActive) cont.resume(Unit) }
            })
            cont.invokeOnCancellation { engine.stop(); _amp.value = 0f }
            if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.ERROR) {
                if (cont.isActive) cont.resume(Unit)
            }
        }
    }

    override fun stop() { tts?.stop(); _amp.value = 0f }

    fun shutdown() { tts?.shutdown(); tts = null }

    /** English voices installed on this device (for the Settings picker). */
    suspend fun availableVoices(): List<String> =
        runCatching { engine().voices.orEmpty().filter { it.locale.language == "en" }.map { it.name }.sorted() }.getOrDefault(emptyList())

    companion object {
        /** Android exposes no gender, so rank by name hints and quality. A user-chosen voice always wins. */
        fun pickVoice(engine: TextToSpeech, preferred: String): Voice? {
            val voices = engine.voices.orEmpty().filter { it.locale.language == "en" }
            if (preferred.isNotBlank()) voices.firstOrNull { it.name == preferred }?.let { return it }
            return voices.maxByOrNull { score(it) }
        }

        fun score(v: Voice): Int {
            val n = v.name.lowercase()
            var s = 0
            if (v.locale.country == "US") s += 3 else if (v.locale.country == "GB") s += 2
            if (n.contains("female")) s += 20
            if (n.contains("male") && !n.contains("female")) s -= 20
            if (Regex("x-(tpf|iob|sfg|tpc|gba)").containsMatchIn(n)) s += 10
            if (v.quality >= Voice.QUALITY_HIGH) s += 4
            if (!v.isNetworkConnectionRequired) s += 1
            return s
        }
    }
}

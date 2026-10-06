package com.friday.assistant.voice

import com.friday.assistant.settings.FridaySettings
import com.friday.assistant.settings.TtsProviderType
import com.friday.assistant.tts.TTSProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Output side of the assistant; implemented by [VoiceEngine] and faked in tests. */
interface Speaker {
    val amplitude: StateFlow<Float>
    /** What is being spoken right now ("" when silent). */
    val currentSpeech: String
    suspend fun speak(request: SpeechRequest)
    fun stop()
}

/**
 * Voice Engine 2.0: multi-tier TTS with automatic fallback (Tier A cloud neural → Tier B other cloud → Tier C Android),
 * sentence queue with subtitle callbacks, audio focus, and a single place to interrupt everything.
 * Tiers without credentials are skipped; Android TTS is always the last resort.
 */
class VoiceEngine(
    private val settings: () -> FridaySettings,
    private val tier: (TtsProviderType) -> TTSProvider?,
    private val fallback: TTSProvider,
    private val focus: FocusController,
    val interrupts: SpeechInterruptController = SpeechInterruptController(),
) : Speaker {
    private val _amp = MutableStateFlow(0f)
    override val amplitude: StateFlow<Float> = _amp.asStateFlow()
    override val currentSpeech: String get() = interrupts.currentText
    @Volatile private var active: List<TTSProvider> = listOf(fallback)
    @Volatile private var speakJob: Job? = null
    /** Name of the provider that spoke last (for Diagnostics). */
    @Volatile var lastProvider: String = ""
        private set

    init { interrupts.attach { stop() } }

    /** Providers that would be tried, in order, for the current settings. */
    fun plan(): List<TTSProvider> {
        val order = settings().ttsPriority.filter { it != TtsProviderType.ANDROID }
        val tiers = order.mapNotNull { runCatching { tier(it) }.getOrNull() }
        return tiers + fallback
    }

    override suspend fun speak(request: SpeechRequest) {
        if (request.chunks.isEmpty()) return
        interrupts.onSpeechStart(request.chunks.joinToString(" ") { it.speech })
        coroutineScope {
            speakJob = coroutineContextJob()
            val plan = plan()
            active = plan
            val ampJob = launch { plan.forEach { p -> launch { p.amplitude.collect { _amp.value = it } } } }
            focus.acquire(settings().audioFocusMode) { interrupts.interrupt() }
            try {
                request.chunks.forEachIndexed { i, chunk ->
                    request.onChunkStart(i)
                    speakChunk(plan, chunk.speech, request)
                }
            } finally {
                focus.release()
                ampJob.cancel()
                _amp.value = 0f
                interrupts.onSpeechEnd()
                speakJob = null
            }
        }
    }

    private suspend fun speakChunk(plan: List<TTSProvider>, text: String, request: SpeechRequest) {
        var last: Exception? = null
        for (p in plan) {
            try {
                p.speak(text, request.emphasis, request.onFirstAudio)
                lastProvider = p.name
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e // try the next tier
            }
        }
        throw last ?: IllegalStateException("No TTS provider")
    }

    override fun stop() {
        active.forEach { it.stop() }
        fallback.stop()
        speakJob?.cancel()
    }

    private suspend fun coroutineContextJob(): Job? = kotlin.coroutines.coroutineContext[Job]
}

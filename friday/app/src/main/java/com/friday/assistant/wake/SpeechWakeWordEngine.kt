package com.friday.assistant.wake

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * V1 wake word: a restart loop around the platform recognizer, filtered by [WakeWordMatcher].
 * It is NOT low-power. Failures back off (2s -> 30s) so it never spins, and it only runs while the
 * Foreground Service with its visible notification is up. See README "Known limitations".
 */
class SpeechWakeWordEngine(
    private val context: Context,
    private val language: String = "ko-KR",
) : WakeWordEngine {
    private val main = Handler(Looper.getMainLooper())
    private val _running = MutableStateFlow(false)
    override val running: StateFlow<Boolean> = _running.asStateFlow()
    private var recognizer: SpeechRecognizer? = null
    private var callback: ((String) -> Unit)? = null
    private var paused = false
    private var failures = 0

    override fun start(onWake: (String) -> Unit) = main.post {
        callback = onWake
        paused = false
        failures = 0
        _running.value = true
        listen()
    }.let {}

    override fun stop() { main.post { _running.value = false; callback = null; release() } }
    override fun pause() { main.post { paused = true; release() } }
    override fun resume() { main.post { if (_running.value && paused) { paused = false; listen() } } }

    private fun listen() {
        if (!_running.value || paused) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) { scheduleRetry(); return }
        release()
        val sr = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { failures = 0 }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {
                check(partialResults)
            }
            override fun onResults(results: Bundle?) {
                if (!check(results)) listenSoon(150)
            }
            override fun onError(error: Int) {
                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) listenSoon(200)
                else scheduleRetry()
            }
        })
        sr.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName),
        )
    }

    private fun check(bundle: Bundle?): Boolean {
        val texts = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        for (t in texts) {
            val m = WakeWordMatcher.match(t) ?: continue
            val cb = callback ?: return false
            paused = true
            release()
            cb(m.remainder)
            return true
        }
        return false
    }

    private fun listenSoon(delayMs: Long) = main.postDelayed({ listen() }, delayMs)

    private fun scheduleRetry() {
        failures++
        listenSoon((2_000L * failures).coerceAtMost(30_000L))
    }

    private fun release() {
        recognizer?.let { runCatching { it.cancel(); it.destroy() } }
        recognizer = null
        main.removeCallbacksAndMessages(null)
    }
}

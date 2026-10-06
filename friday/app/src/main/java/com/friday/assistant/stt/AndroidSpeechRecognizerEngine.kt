package com.friday.assistant.stt

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Korean speech recognition through the platform SpeechRecognizer. Must run on the main thread. */
class AndroidSpeechRecognizerEngine(
    private val context: Context,
    private val language: String = "ko-KR",
    private val defaultTimeoutMs: Long = 12_000,
) : SpeechRecognizerEngine {
    private val _state = MutableStateFlow(SttState.IDLE)
    override val state: StateFlow<SttState> = _state.asStateFlow()
    private val _partial = MutableStateFlow("")
    override val partial: StateFlow<String> = _partial.asStateFlow()
    private val _level = MutableStateFlow(0f)
    override val level: StateFlow<Float> = _level.asStateFlow()
    private var recognizer: SpeechRecognizer? = null
    private var active: CompletableDeferred<SttResult>? = null

    override suspend fun listenOnce(timeoutMs: Long?): SttResult = withContext(Dispatchers.Main.immediate) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            _state.value = SttState.ERROR
            return@withContext SttResult.Failure(SttError.NO_PERMISSION)
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _state.value = SttState.ERROR
            return@withContext SttResult.Failure(SttError.UNAVAILABLE)
        }
        cancel()
        _partial.value = ""
        val result = CompletableDeferred<SttResult>()
        active = result
        val sr = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { _state.value = SttState.LISTENING }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { _level.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { _state.value = SttState.PROCESSING }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { _partial.value = it }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                result.complete(if (text.isEmpty()) SttResult.Empty else SttResult.Text(text))
            }
            override fun onError(error: Int) {
                result.complete(
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SttResult.Empty
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SttResult.Failure(SttError.NO_PERMISSION)
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                            SttResult.Failure(SttError.NETWORK)
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> SttResult.Failure(SttError.BUSY)
                        SpeechRecognizer.ERROR_CLIENT -> SttResult.Empty // we cancelled it ourselves
                        else -> SttResult.Failure(SttError.OTHER)
                    },
                )
            }
        })
        sr.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName),
        )
        _state.value = SttState.LISTENING
        try {
            val r = withTimeoutOrNull(timeoutMs ?: defaultTimeoutMs) { result.await() } ?: SttResult.Failure(SttError.TIMEOUT)
            _state.value = if (r is SttResult.Failure) SttState.ERROR else SttState.IDLE
            r
        } finally {
            _level.value = 0f
            release(sr)
        }
    }

    override fun cancel() {
        active?.complete(SttResult.Empty)
        active = null
        recognizer?.let { release(it) }
    }

    private fun release(sr: SpeechRecognizer) {
        runCatching { sr.cancel(); sr.destroy() }
        if (recognizer === sr) recognizer = null
        if (_state.value != SttState.ERROR) _state.value = SttState.IDLE
    }
}

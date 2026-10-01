package com.jarvis.assistant.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.jarvis.assistant.util.JLog
import com.jarvis.assistant.util.Perms
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class AndroidSpeechRecognizer(private val context: Context) : SpeechRecognizerEngine {
    @Volatile
    private var onDeviceUnusable = false

    override fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    override suspend fun listen(request: ListenRequest, callbacks: ListenCallbacks): SttResult {
        if (!Perms.hasMic(context)) return SttResult.Failure(SttError.PERMISSION)
        if (!isAvailable()) return SttResult.Failure(SttError.UNAVAILABLE)

        val wantOnDevice = request.preferOnDevice && !onDeviceUnusable &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

        val first = runSession(request, callbacks, wantOnDevice)
        if (wantOnDevice && first is SttResult.Failure && first.code in LANGUAGE_ERRORS) {
            // The offline pack for this language is not installed; use the normal recognizer from now on.
            onDeviceUnusable = true
            return runSession(request, callbacks, false)
        }
        return first
    }

    private suspend fun runSession(request: ListenRequest, callbacks: ListenCallbacks, onDevice: Boolean): SttResult =
        withContext(Dispatchers.Main.immediate) {
            var recognizer: SpeechRecognizer? = null
            try {
                withTimeoutOrNull(request.maxDurationMs + 4_000) {
                    suspendCancellableCoroutine<SttResult> { cont ->
                        val created = try {
                            if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                            } else {
                                SpeechRecognizer.createSpeechRecognizer(context)
                            }
                        } catch (e: Exception) {
                            JLog.w("STT", "Could not create recognizer", e)
                            cont.resume(SttResult.Failure(SttError.UNAVAILABLE))
                            return@suspendCancellableCoroutine
                        }
                        recognizer = created

                        fun finish(result: SttResult) {
                            if (cont.isActive) cont.resume(result)
                        }

                        created.setRecognitionListener(object : RecognitionListener {
                            override fun onReadyForSpeech(params: Bundle?) = callbacks.onReady()
                            override fun onBeginningOfSpeech() {}
                            override fun onRmsChanged(rmsdB: Float) {
                                callbacks.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
                            }

                            override fun onBufferReceived(buffer: ByteArray?) {}
                            override fun onEndOfSpeech() {}
                            override fun onEvent(eventType: Int, params: Bundle?) {}

                            override fun onPartialResults(partialResults: Bundle?) {
                                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                    ?.firstOrNull()?.let(callbacks.onPartial)
                            }

                            override fun onResults(results: Bundle?) {
                                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                    ?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
                                finish(
                                    if (list.isEmpty()) SttResult.NoSpeech
                                    else SttResult.Text(list.first(), list),
                                )
                            }

                            override fun onError(error: Int) {
                                finish(mapError(error))
                            }
                        })

                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, request.languageTag)
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, request.languageTag)
                            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, request.preferOffline)
                            putExtra(
                                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                                request.silenceMs,
                            )
                            putExtra(
                                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                                request.silenceMs,
                            )
                        }
                        try {
                            created.startListening(intent)
                        } catch (e: Exception) {
                            JLog.w("STT", "startListening failed", e)
                            finish(SttResult.Failure(SttError.UNAVAILABLE))
                        }
                    }
                } ?: SttResult.Failure(SttError.TIMEOUT)
            } finally {
                // Runs on cancellation too, so the microphone is always released.
                recognizer?.let {
                    try {
                        it.cancel()
                        it.destroy()
                    } catch (e: Exception) {
                        JLog.w("STT", "destroy failed", e)
                    }
                }
                callbacks.onLevel(0f)
            }
        }

    private fun mapError(code: Int): SttResult = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SttResult.NoSpeech
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SttResult.Failure(SttError.PERMISSION, code)
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
            SttResult.Failure(SttError.NETWORK, code)
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> SttResult.Failure(SttError.BUSY, code)
        SpeechRecognizer.ERROR_CLIENT -> SttResult.Failure(SttError.OTHER, code)
        else -> SttResult.Failure(SttError.OTHER, code)
    }

    private companion object {
        // ERROR_LANGUAGE_NOT_SUPPORTED = 12, ERROR_LANGUAGE_UNAVAILABLE = 13
        val LANGUAGE_ERRORS = setOf(12, 13)
    }
}

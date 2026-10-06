package com.friday.assistant.core

import com.friday.assistant.ai.AiException
import com.friday.assistant.stt.SttError

/** One bilingual line per failure: [speech] in English for TTS, [subtitle] in Korean for the screen. */
data class Spoken(val speech: String, val subtitle: String)

object Errors {
    fun ai(e: Throwable): Spoken = when (e) {
        is AiException.NotConfigured -> Spoken(
            "My AI core isn't configured yet. Please add an API key in Settings.",
            "AI가 아직 설정되지 않았습니다. 설정에서 API 키를 입력해 주세요.",
        )
        is AiException.NoInternet -> Spoken("I can't reach the internet right now.", "인터넷에 연결할 수 없습니다.")
        is AiException.InvalidKey -> Spoken("The API key was rejected. Please check it in Settings.", "API 키가 올바르지 않습니다. 설정에서 확인해 주세요.")
        is AiException.RateLimited -> Spoken("The AI service is rate limited. Please try again shortly.", "AI 서비스 요청 한도에 도달했습니다. 잠시 후 다시 시도해 주세요.")
        is AiException.Timeout -> Spoken("The AI service took too long to answer.", "AI 응답이 너무 오래 걸립니다.")
        is AiException.Server -> Spoken("The AI service had a problem. Please try again.", "AI 서비스에 문제가 발생했습니다. 다시 시도해 주세요.")
        is AiException.BadResponse -> Spoken("I got an unreadable answer from the AI.", "AI의 응답을 해석할 수 없습니다.")
        else -> Spoken("Something went wrong while thinking.", "처리 중 오류가 발생했습니다.")
    }

    fun stt(e: SttError): Spoken = when (e) {
        SttError.NO_PERMISSION -> Spoken("I need microphone permission to hear you.", "음성 인식을 위해 마이크 권한이 필요합니다.")
        SttError.NETWORK -> Spoken("Speech recognition needs a connection right now.", "음성 인식에 인터넷 연결이 필요합니다.")
        SttError.TIMEOUT, SttError.NO_MATCH -> Spoken("I didn't catch that.", "잘 듣지 못했습니다.")
        SttError.BUSY -> Spoken("The microphone is busy. Please try again.", "마이크가 사용 중입니다. 다시 시도해 주세요.")
        SttError.UNAVAILABLE -> Spoken("Speech recognition isn't available on this device.", "이 기기에서는 음성 인식을 사용할 수 없습니다.")
        SttError.OTHER -> Spoken("Speech recognition failed. Please try again.", "음성 인식에 실패했습니다. 다시 시도해 주세요.")
    }

    val empty = Spoken("I didn't hear anything.", "아무 말도 들리지 않았습니다.")
    val offlineNoLocal = Spoken(
        "I'm offline, so I can only run on-device commands like time, battery or flashlight.",
        "오프라인 상태입니다. 시간, 배터리, 손전등 같은 기기 명령만 실행할 수 있습니다.",
    )
    val cancelled = Spoken("Okay, cancelled.", "취소했습니다.")
}

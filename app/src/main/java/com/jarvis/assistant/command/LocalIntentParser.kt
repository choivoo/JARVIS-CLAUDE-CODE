package com.jarvis.assistant.command

import com.jarvis.assistant.ai.AiAction
import com.jarvis.assistant.ai.AiReply
import com.jarvis.assistant.speech.WakeWordMatcher
import com.jarvis.assistant.weather.OpenMeteoWeatherProvider
import java.time.LocalTime

/**
 * Rule-based Korean command understanding used when the AI is unreachable, not configured, or
 * returns something unusable. It covers the core device commands so JARVIS stays useful offline.
 */
object LocalIntentParser {
    private val cities = listOf(
        "서울", "부산", "인천", "대구", "대전", "광주", "울산", "수원", "제주", "세종", "성남", "고양", "용인",
        "창원", "청주", "전주", "포항", "춘천", "강릉", "도쿄", "오사카", "뉴욕", "런던", "파리", "베이징", "상하이",
    )
    private val appNamesEn = mapOf(
        "유튜브" to "YouTube", "유튜브뮤직" to "YouTube Music", "크롬" to "Chrome", "카카오톡" to "KakaoTalk",
        "지도" to "Maps", "구글지도" to "Google Maps", "카메라" to "the camera", "계산기" to "the calculator",
        "전화" to "the phone app", "갤러리" to "the gallery", "사진" to "Photos", "지메일" to "Gmail",
        "네이버" to "Naver", "넷플릭스" to "Netflix", "스포티파이" to "Spotify", "플레이스토어" to "the Play Store",
    )
    private val appSuffix = Regex("(?:을|를|좀|앱|어플|어플리케이션|애플리케이션|프로그램)$")

    private data class Quad(val a: String, val b: String, val c: String, val d: String)

    private fun reply(speech: String, subtitle: String, type: String? = null, vararg params: Pair<String, String>) =
        AiReply(speech, subtitle, type?.let { AiAction(it, params.toMap()) })

    fun parse(input: String, now: LocalTime = LocalTime.now()): AiReply? {
        val wake = WakeWordMatcher.match(input)
        val text = (if (wake != null) wake.trailingText.orEmpty() else input).trim()
        if (text.isEmpty()) return null
        val t = text.replace(Regex("\\s+"), " ")

        // Timer ("10분 타이머", "30초 뒤에 알려줘")
        if (t.contains("타이머") || Regex("\\d+\\s*(?:분|초|시간)\\s*(?:뒤|후)에?\\s*(?:알려|깨워)").containsMatchIn(t)) {
            val h = Regex("(\\d+)\\s*시간").find(t)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val m = Regex("(\\d+)\\s*분").find(t)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val sec = Regex("(\\d+)\\s*초").find(t)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            if (h + m + sec == 0) return reply("How long should the timer run?", "타이머를 몇 분으로 맞출까요?")
            val spoken = listOfNotNull(
                h.takeIf { it > 0 }?.let { "$it hour${if (it > 1) "s" else ""}" },
                m.takeIf { it > 0 }?.let { "$it minute${if (it > 1) "s" else ""}" },
                sec.takeIf { it > 0 }?.let { "$it second${if (it > 1) "s" else ""}" },
            ).joinToString(" ")
            return reply(
                "Starting a timer for $spoken.", "${listOfNotNull(h.takeIf { it > 0 }?.let { "${it}시간" }, m.takeIf { it > 0 }?.let { "${it}분" }, sec.takeIf { it > 0 }?.let { "${it}초" }).joinToString(" ")} 타이머를 시작합니다.",
                "SET_TIMER", "hours" to h.toString(), "minutes" to m.toString(), "seconds" to sec.toString(),
            )
        }

        // Notes
        Regex("^(.+?)\\s*(?:라고|이라고)?\\s*메모(?:해|해줘|해 줘|하라고)").find(t)?.let {
            val note = it.groupValues[1].trim()
            if (note.isNotEmpty()) return reply("I'll make a note of that.", "메모해 두겠습니다.", "NOTE_SAVE", "text" to note)
        }
        if (Regex("메모\\s*(?:읽어|보여|알려|목록)").containsMatchIn(t)) {
            return reply("Let me read your notes.", "메모를 확인합니다.", "NOTE_LIST")
        }

        // Navigation
        Regex("^(.+?)(?:까지)?\\s*(?:길\\s*안내|길찾기|내비|네비|가는\\s*길)").find(t)?.let {
            val dest = it.groupValues[1].trim()
            if (dest.isNotEmpty()) return reply("Starting directions.", "길 안내를 시작합니다.", "NAVIGATE", "destination" to dest)
        }

        // Camera / connectivity settings
        if (Regex("카메라").containsMatchIn(t) && Regex("열어|켜|실행|찍").containsMatchIn(t)) {
            return reply("Opening the camera.", "카메라를 엽니다.", "OPEN_CAMERA")
        }
        for ((word, target, en, ko) in listOf(
            Quad("와이파이|wifi|와이 파이", "wifi", "Wi-Fi settings", "와이파이 설정"),
            Quad("블루투스", "bluetooth", "Bluetooth settings", "블루투스 설정"),
            Quad("비행기\\s*모드", "airplane", "airplane mode settings", "비행기 모드 설정"),
        )) {
            if (Regex(word, RegexOption.IGNORE_CASE).containsMatchIn(t) && Regex("열어|켜|꺼|설정|끄").containsMatchIn(t)) {
                return reply("Opening $en.", "${ko}을 엽니다.", "OPEN_SETTINGS", "target" to target)
            }
        }

        // Alarm
        if (t.contains("알람") || t.contains("깨워")) {
            val time = KoreanTimeParser.parse(t, now) ?: return reply(
                "What time should I set the alarm for?", "몇 시에 알람을 맞출까요?",
            )
            val tomorrow = t.contains("내일")
            return reply(
                "Setting an alarm for ${if (tomorrow) "tomorrow at " else ""}${KoreanTimeParser.speakEn(time)}.",
                "${if (tomorrow) "내일 " else ""}${KoreanTimeParser.speakKo(time)}에 알람을 설정합니다.",
                "SET_ALARM", "hour" to time.hour.toString(), "minutes" to time.minute.toString(),
            )
        }

        // Flashlight
        if (Regex("손전등|플래시|랜턴|라이트").containsMatchIn(t)) {
            val off = Regex("꺼|끄|off|종료").containsMatchIn(t)
            val on = Regex("켜|켤|on|밝혀").containsMatchIn(t)
            if (off || on) {
                return if (off) {
                    reply("Turning the flashlight off.", "손전등을 끕니다.", "FLASHLIGHT_OFF")
                } else {
                    reply("Turning the flashlight on.", "손전등을 켭니다.", "FLASHLIGHT_ON")
                }
            }
        }

        // Volume
        if (Regex("볼륨|음량|소리").containsMatchIn(t)) {
            Regex("(\\d{1,3})\\s*(?:퍼센트|%|프로)").find(t)?.let {
                val level = it.groupValues[1].toInt().coerceIn(0, 100)
                return reply("Setting the volume to $level percent.", "볼륨을 ${level}%로 설정합니다.", "SET_VOLUME", "level" to level.toString())
            }
            if (Regex("올려|키워|높여|크게|증가").containsMatchIn(t)) {
                return reply("Turning the volume up.", "볼륨을 올립니다.", "VOLUME_UP")
            }
            if (Regex("낮춰|줄여|내려|작게|감소").containsMatchIn(t)) {
                return reply("Turning the volume down.", "볼륨을 낮춥니다.", "VOLUME_DOWN")
            }
        }

        // Battery
        if (t.contains("배터리") || t.contains("잔량")) {
            return reply("Checking the battery.", "배터리를 확인합니다.", "GET_BATTERY")
        }

        // Weather
        if (Regex("날씨|기온|우산|비 올|비가 올|눈 올").containsMatchIn(t)) {
            val day = if (t.contains("내일")) "tomorrow" else "today"
            val city = cities.firstOrNull { t.contains(it) }
            val cityEn = city?.let { OpenMeteoWeatherProvider.normalizeCity(it) }
            return reply(
                "I'll check the weather${if (cityEn != null) " for $cityEn" else ""}.",
                "${if (city != null) "${city}의 " else ""}날씨를 확인하겠습니다.",
                "WEATHER", "city" to (cityEn ?: ""), "day" to day,
            )
        }

        // YouTube search
        Regex("유튜브(?:에서)?\\s*(.+?)\\s*(?:검색|찾아|틀어)").find(t)?.let {
            val q = it.groupValues[1].trim().removeSuffix("을").removeSuffix("를").trim()
            if (q.isNotEmpty() && !q.contains("뮤직")) {
                return reply("Searching YouTube.", "유튜브에서 '$q'을(를) 검색합니다.", "SEARCH_YOUTUBE", "query" to q)
            }
        }

        // Web search
        Regex("(?:구글|네이버|웹|인터넷)(?:에서)?\\s*(.+?)\\s*(?:검색|찾아)").find(t)?.let {
            val q = it.groupValues[1].trim().removeSuffix("을").removeSuffix("를").trim()
            if (q.isNotEmpty() && q != "웹" && q != "검색") {
                return reply("Searching the web.", "웹에서 '$q'을(를) 검색합니다.", "SEARCH_WEB", "query" to q)
            }
        }
        if (Regex("^(?:웹|인터넷)?\\s*검색").containsMatchIn(t) || t.endsWith("검색해줘") && t.length <= 8) {
            return reply("What would you like me to search for?", "무엇을 검색할까요?")
        }

        // Music
        if (Regex("다음\\s*(?:곡|노래)").containsMatchIn(t)) return reply("Next track.", "다음 곡으로 넘깁니다.", "MUSIC_NEXT")
        if (Regex("이전\\s*(?:곡|노래)").containsMatchIn(t)) return reply("Previous track.", "이전 곡으로 돌아갑니다.", "MUSIC_PREVIOUS")
        if (Regex("일시\\s*정지|멈춰|정지").containsMatchIn(t) && Regex("음악|노래|재생|일시").containsMatchIn(t)) {
            return reply("Pausing the music.", "음악을 일시정지합니다.", "MUSIC_PAUSE")
        }
        if (Regex("음악|노래").containsMatchIn(t) && Regex("재생|틀어|켜").containsMatchIn(t) && !t.contains("유튜브")) {
            return reply("Playing music.", "음악을 재생합니다.", "MUSIC_PLAY")
        }

        // Time
        if (Regex("몇\\s*시|시간|시각").containsMatchIn(t) && Regex("알려|뭐|몇|지금|현재").containsMatchIn(t)) {
            return reply("Let me check the time.", "시간을 확인합니다.", "GET_TIME")
        }

        // Settings
        if (t.contains("설정") && Regex("열어|켜|실행|가줘").containsMatchIn(t)) {
            return reply("Opening settings.", "설정을 엽니다.", "OPEN_SETTINGS")
        }

        // Open an app
        Regex("^(.+?)\\s*(?:열어|실행|켜|켜줘|띄워|시작)").find(t)?.let {
            var name = it.groupValues[1].trim()
            name = name.replace(appSuffix, "").trim()
            if (name.isNotEmpty() && name.length <= 20) {
                val en = appNamesEn[name.replace(" ", "")] ?: "the application"
                return reply("Opening $en.", "${name}을(를) 엽니다.", "OPEN_APP", "app" to name)
            }
        }
        return null
    }
}

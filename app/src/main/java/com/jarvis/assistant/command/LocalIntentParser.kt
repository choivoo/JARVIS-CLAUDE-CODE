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

        // ---- v1.2 shortcuts ----
        parseV12(t)?.let { return it }

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

    private fun act(en: String, ko: String, type: String, vararg params: Pair<String, String>) = reply(en, ko, type, *params)

    /** Phrases for the v1.2 command set. Returns null when nothing matches. */
    private fun parseV12(t: String): AiReply? {
        fun has(r: String) = Regex(r, RegexOption.IGNORE_CASE).containsMatchIn(t)

        // calculator: "3 더하기 5", "20% of 50", "12 곱하기 8 계산해줘"
        if (has("계산|더하기|빼기|곱하기|나누기|제곱근|루트") || Regex("\\d+\\s*[+\\-*/x×÷]\\s*\\d+").containsMatchIn(t)) {
            val expr = t.replace(Regex("계산해줘|계산해|얼마야|얼마|는|은|\\?|\\.|해줘|알려줘"), "").trim()
            if (expr.any { it.isDigit() }) return act("Let me work that out.", "계산해 보겠습니다.", "CALCULATE", "expression" to expr)
        }
        // currency
        Regex("(\\d+(?:\\.\\d+)?)\\s*(달러|원|엔|유로|위안|파운드)\\s*(?:은|는|이)?\\s*(달러|원|엔|유로|위안|파운드)?").find(t)?.let {
            if (has("환전|환율|얼마|몇")) {
                val from = it.groupValues[2]
                val to = it.groupValues[3].ifEmpty { if (from == "원") "달러" else "원" }
                if (from != to) return act("Checking the exchange rate.", "환율을 확인합니다.", "CONVERT_CURRENCY", "amount" to it.groupValues[1], "from" to from, "to" to to)
            }
        }
        // units: "10킬로미터는 몇 마일"
        Regex("(\\d+(?:\\.\\d+)?)\\s*(킬로미터|미터|센티미터|마일|피트|인치|킬로그램|그램|파운드|리터|밀리리터|평|km|cm|kg|lb|mph|mi)\\s*(?:는|은|이|을|를)?\\s*(?:몇|얼마)?\\s*(킬로미터|미터|센티미터|마일|피트|인치|킬로그램|그램|파운드|리터|밀리리터|제곱미터|km|cm|kg|lb|mi|m2)?").find(t)?.let {
            val to = it.groupValues[3]
            if (to.isNotEmpty() && has("몇|변환|환산")) {
                return act("Converting.", "단위를 변환합니다.", "CONVERT_UNITS", "value" to it.groupValues[1], "from" to it.groupValues[2], "to" to to)
            }
        }
        // dice / coin / random
        if (has("주사위")) return act("Rolling.", "주사위를 굴립니다.", "ROLL_DICE")
        if (has("동전")) return act("Flipping.", "동전을 던집니다.", "FLIP_COIN")
        Regex("(\\d+)\\s*(?:부터|에서|~)\\s*(\\d+)\\s*(?:사이|까지)?.*(?:숫자|랜덤|아무)").find(t)?.let {
            return act("Choosing.", "숫자를 뽑습니다.", "RANDOM_NUMBER", "min" to it.groupValues[1], "max" to it.groupValues[2])
        }
        if (has("비밀번호.*(만들|생성)")) return act("Generating a password.", "비밀번호를 생성합니다.", "GENERATE_PASSWORD")
        // fun
        if (has("농담|웃긴")) return act("Allow me.", "하나 들려드리겠습니다.", "TELL_JOKE")
        if (has("명언|동기부여")) return act("Here is one.", "명언을 들려드리겠습니다.", "QUOTE")
        if (has("재미있는 사실|상식|잡학")) return act("Here is a fact.", "재미있는 사실입니다.", "FUN_FACT")
        if (has("호흡|명상|숨쉬기|숨 쉬기")) return act("Let's breathe.", "호흡 명상을 시작합니다.", "BREATHE")
        // time & date
        if (has("무슨 요일|오늘 며칠|오늘 날짜|며칠이야|날짜 알려")) return act("Checking the date.", "날짜를 확인합니다.", "GET_DATE")
        Regex("(.+?)\\s*(?:은|는)?\\s*(?:지금)?\\s*(?:몇\\s*시|시간)").find(t)?.let {
            val city = cities.firstOrNull { c -> t.contains(c) } ?: Regex("(뉴욕|런던|파리|도쿄|베이징|시드니|두바이|방콕|싱가포르|로스앤젤레스|모스크바)").find(t)?.value
            if (city != null && !t.contains("지금 시간") ) return act("Checking.", "시간대를 확인합니다.", "WORLD_TIME", "city" to city)
        }
        Regex("(\\d+)월\\s*(\\d+)일.*(?:며칠|남았|디데이)").find(t)?.let {
            return act("Counting.", "남은 날짜를 계산합니다.", "DAYS_UNTIL", "month" to it.groupValues[1], "day" to it.groupValues[2])
        }
        if (has("스톱워치|초시계")) {
            val type = when {
                has("시작|켜") -> "STOPWATCH_START"
                has("멈춰|정지|중지|끝") -> "STOPWATCH_STOP"
                has("랩") -> "STOPWATCH_LAP"
                has("초기화|리셋") -> "STOPWATCH_RESET"
                else -> "STOPWATCH_STATUS"
            }
            return act("Stopwatch.", "스톱워치 명령입니다.", type)
        }
        // feeds
        if (has("뉴스|헤드라인")) {
            val topic = Regex("(.+?)\\s*(?:관련|에 대한)?\\s*뉴스").find(t)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() && it != "오늘" && it != "최신" }
            return act("Fetching the headlines.", "뉴스를 가져옵니다.", "NEWS", "topic" to (topic ?: ""))
        }
        if (has("비트코인|이더리움|리플|도지코인|솔라나|코인 시세|코인 가격")) {
            val coin = Regex("(비트코인|이더리움|리플|도지코인|솔라나)").find(t)?.value ?: "비트코인"
            return act("Checking the market.", "시세를 확인합니다.", "CRYPTO_PRICE", "coin" to coin)
        }
        if (has("미세먼지|초미세|대기질|공기질")) return act("Checking the air quality.", "대기질을 확인합니다.", "AIR_QUALITY", "city" to (cities.firstOrNull { t.contains(it) }?.let { com.jarvis.assistant.weather.OpenMeteoWeatherProvider.normalizeCity(it) } ?: ""))
        if (has("자외선|uv")) return act("Checking the UV index.", "자외선 지수를 확인합니다.", "UV_INDEX")
        if (has("일출|일몰|해 뜨|해 지|해가 (뜨|지)")) return act("Checking the sun times.", "일출·일몰 시간을 확인합니다.", "SUN_TIMES")
        if (has("주간 날씨|이번 주 날씨|일주일 날씨|한 주 날씨")) return act("Checking the week ahead.", "주간 예보를 확인합니다.", "WEATHER_WEEK")
        if (has("내 위치|여기가 어디|지금 어디")) return act("Locating you.", "현재 위치를 확인합니다.", "WHERE_AM_I")
        if (has("위치 공유|위치 보내")) return act("Preparing your location.", "위치를 공유합니다.", "SHARE_LOCATION")
        // reminders / lists
        Regex("(\\d+)\\s*(분|시간)\\s*(?:뒤|후)에\\s*(.+?)\\s*(?:라고)?\\s*(?:알려|리마인드|알림)").find(t)?.let {
            val n = it.groupValues[1]
            val key = if (it.groupValues[2] == "분") "minutes_from_now" else "hours_from_now"
            return act("I'll remind you.", "알려드리겠습니다.", "REMINDER_SET", "text" to it.groupValues[3].trim(), key to n)
        }
        Regex("(.+?)\\s*(?:라고)?\\s*(?:알림|리마인드)\\s*(?:해|설정|맞춰)").find(t)?.let {
            val time = KoreanTimeParser.parse(t)
            val text = it.groupValues[1].replace(Regex("(오전|오후|내일|오늘)?\\s*\\d{1,2}\\s*시(\\s*\\d{1,2}\\s*분)?(\\s*반)?\\s*에?"), "").trim()
            if (time != null && text.isNotEmpty()) {
                return act("I'll remind you.", "알려드리겠습니다.", "REMINDER_SET", "text" to text, "hour" to time.hour.toString(), "minutes" to time.minute.toString())
            }
        }
        if (has("알림 (목록|확인|뭐)|예정된 알림")) return act("Checking reminders.", "알림을 확인합니다.", "REMINDER_LIST")
        if (has("알림.*(전부|모두).*(취소|삭제)|알림 취소")) return act("Cancelling.", "알림을 취소합니다.", "REMINDER_CANCEL", "which" to "all")
        Regex("(.+?)\\s*(?:을|를)?\\s*(?:장보기|쇼핑)\\s*(?:목록|리스트)에?\\s*(?:추가|넣어)").find(t)?.let {
            return act("Added.", "장보기 목록에 추가합니다.", "TASK_ADD", "text" to it.groupValues[1].trim(), "list" to "shopping")
        }
        Regex("(.+?)\\s*(?:을|를)?\\s*(?:할 ?일|투두)(?: 목록)?에?\\s*(?:추가|넣어)").find(t)?.let {
            return act("Added.", "할 일에 추가합니다.", "TASK_ADD", "text" to it.groupValues[1].trim(), "list" to "todo")
        }
        if (has("장보기 (목록|리스트)")) return act("Here is your list.", "장보기 목록입니다.", "TASK_LIST", "list" to "shopping")
        if (has("할 ?일 (목록|리스트)|투두 (목록|리스트)")) return act("Here is your list.", "할 일 목록입니다.", "TASK_LIST", "list" to "todo")
        Regex("(.+?)\\s*(?:을|를)?\\s*(?:완료|끝냈|다 했)").find(t)?.let {
            return act("Done.", "완료 처리합니다.", "TASK_DONE", "text" to it.groupValues[1].trim())
        }
        if (has("물 (한 ?잔|마셨)")) return act("Logged.", "기록합니다.", "COUNTER_ADD", "name" to "water")
        if (has("커피 (한 ?잔|마셨)")) return act("Logged.", "기록합니다.", "COUNTER_ADD", "name" to "coffee")
        if (has("물 몇 잔")) return act("Checking.", "오늘 기록을 확인합니다.", "COUNTER_GET", "name" to "water")
        // calendar
        if (has("내일 일정")) return act("Checking tomorrow.", "내일 일정을 확인합니다.", "CALENDAR_TOMORROW")
        if (has("오늘 일정|일정 알려|스케줄")) return act("Checking your schedule.", "오늘 일정을 확인합니다.", "CALENDAR_TODAY")
        if (has("다음 일정")) return act("Checking.", "다음 일정을 확인합니다.", "CALENDAR_NEXT")
        if (has("알람 (목록|보여)|설정된 알람")) return act("Opening alarms.", "알람 목록을 엽니다.", "SHOW_ALARMS")
        // routines & briefing
        if (has("브리핑")) return act("Preparing your briefing.", "브리핑을 준비합니다.", "DAILY_BRIEFING")
        Regex("(굿모닝|굿나잇|좋은 아침|잘 ?자|외출|출근|업무|집중)\\s*(?:모드|루틴)?\\s*(?:시작|실행|켜)?").find(t)?.let {
            if (has("루틴|모드|시작|실행") || t.trim() == it.value.trim()) return act("Running your routine.", "루틴을 실행합니다.", "RUN_ROUTINE", "name" to it.groupValues[1])
        }
        if (has("루틴 (목록|뭐)")) return act("Here they are.", "루틴 목록입니다.", "ROUTINE_LIST")
        // device
        if (has("밝기")) {
            Regex("(\\d{1,3})\\s*(?:퍼센트|%)").find(t)?.let { return act("Adjusting brightness.", "밝기를 조절합니다.", "BRIGHTNESS_SET", "level" to it.groupValues[1]) }
            if (has("올려|높여|밝게")) return act("Brighter.", "밝기를 올립니다.", "BRIGHTNESS_UP")
            if (has("낮춰|줄여|어둡게")) return act("Dimmer.", "밝기를 낮춥니다.", "BRIGHTNESS_DOWN")
        }
        if (has("음소거 해제|소리 켜")) return act("Sound on.", "소리를 켭니다.", "UNMUTE")
        if (has("음소거")) return act("Muting.", "음소거합니다.", "MUTE")
        if (has("무음 모드|무음으로")) return act("Silent mode.", "무음 모드로 전환합니다.", "RINGER_SILENT")
        if (has("진동 모드|진동으로")) return act("Vibrate mode.", "진동 모드로 전환합니다.", "RINGER_VIBRATE")
        if (has("소리 모드|벨소리 모드|무음 해제")) return act("Ringer on.", "소리 모드로 전환합니다.", "RINGER_NORMAL")
        if (has("저장 ?공간|용량 얼마")) return act("Checking storage.", "저장 공간을 확인합니다.", "STORAGE_INFO")
        if (has("메모리|램 얼마|ram")) return act("Checking memory.", "메모리를 확인합니다.", "MEMORY_INFO")
        if (has("기기 정보|폰 정보|휴대폰 정보|무슨 폰")) return act("Checking.", "기기 정보를 확인합니다.", "DEVICE_INFO")
        if (has("네트워크|인터넷 (상태|연결)|와이파이 연결")) return act("Checking the network.", "네트워크 상태를 확인합니다.", "NETWORK_INFO")
        if (has("배터리 (온도|상태|충전 시간)|충전 얼마나")) return act("Checking.", "배터리 상세 정보를 확인합니다.", "BATTERY_DETAIL")
        if (has("얼마나 켜져|업타임|부팅한 지")) return act("Checking.", "가동 시간을 확인합니다.", "UPTIME")
        if (has("상태 점검|시스템 점검|시스템 상태|진단")) return act("Running diagnostics.", "시스템을 점검합니다.", "STATUS_REPORT")
        if (has("sos|에스오에스")) return act("Signalling.", "SOS 신호를 보냅니다.", "FLASHLIGHT_SOS")
        if (has("폰 찾아|휴대폰 찾아|내 폰 어디")) return act("Ringing now.", "폰을 울립니다.", "FIND_PHONE")
        if (has("벨 ?소리 (꺼|그만)|울리는 거 (꺼|멈춰)")) return act("Silencing.", "소리를 멈춥니다.", "STOP_FIND_PHONE")
        if (has("주변 밝기|조도")) return act("Reading the light level.", "주변 밝기를 측정합니다.", "AMBIENT_LIGHT")
        if (has("나침반|방위|어느 쪽")) return act("Checking the compass.", "방위를 확인합니다.", "COMPASS")
        if (has("걸음 수|만보기|몇 걸음")) return act("Checking steps.", "걸음 수를 확인합니다.", "STEP_COUNT")
        if (has("고도|기압")) return act("Checking.", "기압과 고도를 확인합니다.", "ALTITUDE")
        // media
        Regex("(.+?)\\s*(?:노래|음악|곡)?\\s*(?:틀어|재생해|들려)").find(t)?.let {
            val q = it.groupValues[1].replace(Regex("(음악|노래|곡)"), "").trim()
            if (q.isNotEmpty() && !has("유튜브") && q.length <= 40 && !q.contains("다음") && !q.contains("이전")) {
                return act("Playing.", "'$q'을(를) 재생합니다.", "PLAY_MUSIC", "query" to q)
            }
        }
        if (has("음악 (멈춰|정지)|노래 (멈춰|정지)")) return act("Stopping.", "정지합니다.", "MUSIC_STOP")
        if (has("앞으로 감|빨리 감")) return act("Forward.", "빨리 감습니다.", "MUSIC_FORWARD")
        if (has("뒤로 감|되감")) return act("Rewinding.", "되감습니다.", "MUSIC_REWIND")
        // web & stores
        Regex("네이버(?:에서)?\\s*(.+?)\\s*(?:검색|찾아)").find(t)?.let { return act("Searching Naver.", "네이버에서 검색합니다.", "SEARCH_SITE", "site" to "naver", "query" to it.groupValues[1]) }
        Regex("(?:위키(?:피디아)?|나무위키)(?:에서)?\\s*(.+?)\\s*(?:검색|찾아)").find(t)?.let { return act("Searching the wiki.", "위키에서 검색합니다.", "SEARCH_SITE", "site" to (if (t.contains("나무")) "namu" else "wikipedia"), "query" to it.groupValues[1]) }
        Regex("(.+?)\\s*(?:이미지|사진)\\s*(?:검색|찾아)").find(t)?.let { return act("Searching images.", "이미지를 검색합니다.", "SEARCH_SITE", "site" to "images", "query" to it.groupValues[1]) }
        Regex("(.+?)\\s*(?:을|를)?\\s*번역").find(t)?.let { return act("Opening the translator.", "번역기를 엽니다.", "SEARCH_SITE", "site" to "translate", "query" to it.groupValues[1]) }
        Regex("(.+?)\\s*(?:앱)?\\s*(?:설치|다운로드)(?:해|하고 싶)").find(t)?.let { return act("Opening the store.", "플레이 스토어에서 찾습니다.", "PLAY_STORE_SEARCH", "query" to it.groupValues[1]) }
        // communication
        Regex("(.+?)\\s*(?:에게|한테)\\s*전화").find(t)?.let { return act("Opening the dialer.", "전화 앱을 엽니다.", "CALL", "name" to it.groupValues[1].trim()) }
        Regex("(.+?)\\s*(?:에게|한테)\\s*(?:문자|메시지)\\s*(.+)?").find(t)?.let {
            val body = it.groupValues[2].replace(Regex("(보내줘|보내|해줘)$"), "").trim()
            return act("Opening messages.", "문자 앱을 엽니다.", "SEND_SMS", "name" to it.groupValues[1].trim(), "text" to body)
        }
        // settings by voice
        if (has("자막 (꺼|끄)")) return act("Subtitles off.", "자막을 끕니다.", "JARVIS_SETTING", "setting" to "subtitles", "value" to "off")
        if (has("자막 (켜|켜줘)")) return act("Subtitles on.", "자막을 켭니다.", "JARVIS_SETTING", "setting" to "subtitles", "value" to "on")
        if (has("(말|목소리) (더 )?빠르게")) return act("Faster.", "말을 빠르게 합니다.", "JARVIS_SETTING", "setting" to "speed", "value" to "faster")
        if (has("(말|목소리) (더 )?느리게|천천히 말")) return act("Slower.", "말을 느리게 합니다.", "JARVIS_SETTING", "setting" to "speed", "value" to "slower")
        if (has("다시 말해|방금 뭐라고|다시 한번")) return act("Certainly.", "다시 말씀드리겠습니다.", "REPEAT")
        if (has("뭘 할 수 있|도움말|기능 알려|무엇을 할 수")) return act("Allow me to explain.", "기능을 안내합니다.", "HELP")
        return null
    }
}

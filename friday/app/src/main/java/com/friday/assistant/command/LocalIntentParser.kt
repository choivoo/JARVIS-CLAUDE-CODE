package com.friday.assistant.command

import java.time.LocalDateTime

/**
 * Fast, offline recognition of unambiguous Korean device commands.
 * Anything it does not clearly understand returns null and goes to the AI instead.
 */
object LocalIntentParser {
    fun parse(input: String, now: LocalDateTime = LocalDateTime.now()): Command? {
        val t = input.trim().trimEnd('.', '?', '!', ' ')
        if (t.isEmpty()) return null
        val n = t.replace(" ", "")

        if (n.contains("알람") && !n.contains("취소") && !n.contains("끄")) {
            KoreanTimeParser.parse(t, now)?.let { p ->
                return Command(
                    CommandType.SET_ALARM,
                    mapOf("hour" to p.hour.toString(), "minute" to p.minute.toString(), "day" to dayName(p.dayOffset), "dayOffset" to p.dayOffset.toString()),
                )
            }
        }


        // ---- briefings -------------------------------------------------------------------------------------
        if (Regex("(저녁브리핑|저녁정리|오늘정리|하루정리|오늘하루정리|마무리브리핑)").containsMatchIn(n)) return Command(CommandType.EVENING_BRIEF)
        if (n.contains("브리핑")) return Command(CommandType.MORNING_BRIEF)

        // ---- notifications ----------------------------------------------------------------------------------
        if (n.contains("알림") && !n.contains("설정")) {
            val appBefore = Regex("""^(.+?)\s*알림""").find(t)?.groupValues?.get(1)?.trim()
                ?.takeIf { it.isNotEmpty() && it !in setOf("새", "최근", "새로운", "내", "모든", "전체", "이번") && it.length <= 12 }
            when {
                Regex("(몇개|개수|몇건)").containsMatchIn(n) -> return Command(CommandType.GET_NOTIFICATION_COUNT)
                Regex("(지워|삭제|없애|닫아|지우)").containsMatchIn(n) -> return Command(CommandType.DISMISS_NOTIFICATION, appParam(appBefore))
                Regex("읽어").containsMatchIn(n) ->
                    return if (appBefore != null) Command(CommandType.READ_NOTIFICATIONS_FROM_APP, mapOf("target" to appBefore)) else Command(CommandType.READ_LATEST_NOTIFICATION)
                Regex("(열어|보여줘.*자세히)").containsMatchIn(n) -> return Command(CommandType.OPEN_NOTIFICATION, appParam(appBefore))
                Regex("(있어|뭐|알려|왔|확인|최근)").containsMatchIn(n) ->
                    return if (appBefore != null) Command(CommandType.READ_NOTIFICATIONS_FROM_APP, mapOf("target" to appBefore)) else Command(CommandType.GET_NOTIFICATIONS)
            }
        }

        // ---- calendar ---------------------------------------------------------------------------------------
        if (Regex("(일정|스케줄)").containsMatchIn(n)) {
            if (Regex("(추가|등록|만들어|잡아|넣어)").containsMatchIn(n)) {
                val ev = EventTextParser.parse(t, now)
                if (ev.title != null) {
                    val p = linkedMapOf("title" to ev.title, "date" to ev.date.toString(), "minute" to ev.minute.toString())
                    ev.hour?.let { p["hour"] = it.toString() }
                    return Command(CommandType.CREATE_EVENT_REQUEST, p)
                }
            } else {
                Regex("""^(.+?)\s*(?:일정|스케줄)\s*(?:찾아|검색)""").find(t)?.let { m ->
                    return Command(CommandType.SEARCH_EVENTS, mapOf("query" to m.groupValues[1].trim()))
                }
                when {
                    Regex("(다음일정|다음스케줄|다가오는)").containsMatchIn(n) -> return Command(CommandType.GET_NEXT_EVENT)
                    n.contains("내일") -> return Command(CommandType.GET_TOMORROW_EVENTS)
                    Regex("(오늘|일정알려|일정있어|일정뭐)").containsMatchIn(n) -> return Command(CommandType.GET_TODAY_EVENTS)
                }
            }
        }

        // ---- media state ------------------------------------------------------------------------------------
        if (Regex("(무슨노래|무슨곡|어떤노래|어떤곡|뭐틀|재생중인|노래제목|곡제목)").containsMatchIn(n)) return Command(CommandType.GET_MEDIA_STATE)

        // ---- device status ----------------------------------------------------------------------------------
        if (n.contains("충전") && Regex("(중이|하고|되고|돼|됐|되나|하는)").containsMatchIn(n) && !Regex("(얼마|몇)").containsMatchIn(n)) return Command(CommandType.GET_CHARGING_STATE)
        if (Regex("(인터넷|와이파이|wifi|블루투스|네트워크|데이터)").containsMatchIn(n) && Regex("(연결|켜져|켜있|돼|됐|되어|되나|상태)").containsMatchIn(n) && !n.contains("설정")) return Command(CommandType.GET_NETWORK_STATE)
        if (Regex("(볼륨|소리|음량).*(몇|얼마|어느)").containsMatchIn(n)) return Command(CommandType.GET_VOLUME)
        if (Regex("(저장공간|저장용량|용량|메모리).*(얼마|남|몇)").containsMatchIn(n)) return Command(CommandType.GET_STORAGE)
        if (Regex("(기기상태|폰상태|핸드폰상태|휴대폰상태|상태요약)").containsMatchIn(n)) return Command(CommandType.GET_DEVICE_STATUS)

        // ---- app control ------------------------------------------------------------------------------------
        Regex("""^(.+?)\s*(?:앱\s*)?설정\s*(?:화면|페이지)?\s*(?:열어|켜|보여|띄워)""").find(t)?.let { m ->
            val raw = m.groupValues[1].replace(Regex("(앱|어플)$"), "").trim()
            val key = raw.replace(" ", "").lowercase()
            val system = mapOf("와이파이" to "wifi", "wifi" to "wifi", "블루투스" to "bluetooth", "디스플레이" to "display", "화면" to "display",
                "소리" to "sound", "배터리" to "battery", "위치" to "location", "앱" to "apps", "알림" to "notifications")
            if (key in system) return Command(CommandType.OPEN_SETTINGS, mapOf("target" to system.getValue(key)))
            if (raw.isNotEmpty() && key !in setOf("시스템", "기기", "휴대폰", "폰", "")) return Command(CommandType.OPEN_APP_SETTINGS, mapOf("target" to raw))
        }
        Regex("""^(.+?)\s*(?:앱|어플)?\s*(?:이|가)?\s*(?:설치돼|설치되어|설치됐|있어|깔려)""").find(t)?.let { m ->
            if (n.contains("앱") || n.contains("설치") || n.contains("깔려")) {
                val q = m.groupValues[1].replace(Regex("(설치된\\s*앱\\s*중|앱\\s*중)"), "").trim()
                if (q.isNotEmpty() && q.length <= 20 && Regex("(설치|깔려|앱)").containsMatchIn(n)) return Command(CommandType.SEARCH_INSTALLED_APPS, mapOf("query" to q))
            }
        }
        if (Regex("(열어|켜|실행|띄워)").containsMatchIn(n)) {
            val key = Regex("^(.+?)(열어|켜|실행|띄워)").find(n)?.groupValues?.get(1)?.replace(Regex("(앱|어플|좀)$"), "").orEmpty()
            when (key) {
                "카메라", "camera" -> return Command(CommandType.OPEN_CAMERA)
                "시계", "알람", "알람앱" -> return Command(CommandType.OPEN_CLOCK)
                "캘린더", "달력", "일정", "구글캘린더" -> return Command(CommandType.OPEN_CALENDAR)
                "지도", "구글지도", "맵" -> return Command(CommandType.OPEN_MAPS)
                "브라우저", "인터넷", "웹브라우저" -> return Command(CommandType.OPEN_BROWSER)
            }
        }

        Regex("""(?:유튜브|유투브|youtube)(?:에서|로)\s*(.+?)\s*(?:검색|찾아|틀어|보여)""", RegexOption.IGNORE_CASE).find(t)?.let {
            return Command(CommandType.YOUTUBE_SEARCH, mapOf("query" to it.groupValues[1]))
        }

        if (Regex("(손전등|플래시|랜턴|라이트)").containsMatchIn(n)) {
            if (Regex("(꺼|끄|끌)").containsMatchIn(n)) return Command(CommandType.FLASHLIGHT_OFF)
            if (Regex("(켜|켤|켜줘|켜봐)").containsMatchIn(n)) return Command(CommandType.FLASHLIGHT_ON)
        }

        if (Regex("(볼륨|소리|음량)").containsMatchIn(n)) {
            Regex("""(\d{1,3})""").find(n)?.let {
                return Command(CommandType.SET_VOLUME, mapOf("percent" to it.groupValues[1].toInt().coerceIn(0, 100).toString()))
            }
            if (Regex("(올려|높여|키워|크게|업)").containsMatchIn(n)) return Command(CommandType.VOLUME_UP)
            if (Regex("(내려|낮춰|줄여|작게|다운)").containsMatchIn(n)) return Command(CommandType.VOLUME_DOWN)
        }

        if (Regex("(다음곡|다음노래|다음트랙|넘겨)").containsMatchIn(n)) return Command(CommandType.NEXT_MEDIA)
        if (Regex("(이전곡|이전노래|전곡|앞곡|이전트랙)").containsMatchIn(n)) return Command(CommandType.PREVIOUS_MEDIA)
        if (Regex("(음악|노래|재생|미디어).*(멈춰|정지|일시정지|꺼|중지)|^(멈춰|일시정지)").containsMatchIn(n)) return Command(CommandType.PAUSE_MEDIA)
        if (Regex("(음악|노래).*(재생|틀어|켜)|^재생해").containsMatchIn(n) && !n.contains("검색")) return Command(CommandType.PLAY_MEDIA)

        if (Regex("(배터리|전지|충전.*(얼마|몇))").containsMatchIn(n)) return Command(CommandType.GET_BATTERY)
        if (Regex("(몇시|시간.*(알려|뭐|몇)|지금시간|현재시간)").containsMatchIn(n)) return Command(CommandType.GET_TIME)
        if (Regex("(며칠|몇일|무슨요일|오늘날짜|날짜.*(알려|뭐))").containsMatchIn(n)) return Command(CommandType.GET_DATE)

        Regex("""^(.+?)\s*(?:좀\s*)?(?:열어|실행|켜|띄워|켜봐|열어봐)(?:줘|주세요|봐)?$""").find(t)?.let { m ->
            val target = m.groupValues[1].replace(Regex("(앱|어플|애플리케이션)$"), "").trim()
            if (target.isNotEmpty() && target.length <= 20) {
                val norm = target.replace(" ", "").lowercase()
                return when {
                    norm in setOf("유튜브", "유투브", "youtube") -> Command(CommandType.OPEN_YOUTUBE)
                    norm in setOf("설정", "세팅", "settings") -> Command(CommandType.OPEN_SETTINGS)
                    else -> Command(CommandType.OPEN_APP, mapOf("target" to target))
                }
            }
        }
        return null
    }

    private fun appParam(app: String?): Map<String, String> = if (app == null) emptyMap() else mapOf("target" to app)

    private fun dayName(offset: Int) = when (offset) { 0 -> "today"; 1 -> "tomorrow"; else -> "later" }
}

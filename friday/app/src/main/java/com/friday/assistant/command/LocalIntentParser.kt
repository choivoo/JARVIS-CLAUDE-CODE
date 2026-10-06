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

    private fun dayName(offset: Int) = when (offset) { 0 -> "today"; 1 -> "tomorrow"; else -> "later" }
}

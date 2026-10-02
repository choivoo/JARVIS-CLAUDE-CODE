package com.jarvis.assistant.routine

import com.jarvis.assistant.data.repository.RoutineRepository

/** Built-in and user-defined routines: a name plus a list of natural-language steps. */
class RoutineBook(private val repo: RoutineRepository) {
    class Routine(val name: String, val intro: Pair<String, String>?, val steps: List<String>, val outro: Pair<String, String>? = null)

    private val builtIn: Map<String, Routine> = listOf(
        Routine(
            "good_morning",
            "Good morning. Let me prepare your briefing." to "좋은 아침입니다. 브리핑을 준비하겠습니다.",
            listOf("지금 시간 알려줘", "오늘 날씨 알려줘", "오늘 일정 알려줘", "내 배터리 얼마나 남았어?", "뉴스 알려줘"),
        ),
        Routine(
            "good_night",
            "Good night. Settling the house in." to "안녕히 주무세요. 마무리 점검을 시작합니다.",
            listOf("내일 일정 알려줘", "손전등 꺼줘", "볼륨 20퍼센트로 설정해", "내 배터리 얼마나 남았어?"),
            "Sleep well. I'll keep watch." to "푹 주무세요. 제가 지키고 있겠습니다.",
        ),
        Routine(
            "leaving_home",
            "Heading out. A quick check first." to "외출 준비를 확인하겠습니다.",
            listOf("오늘 날씨 알려줘", "미세먼지 알려줘", "내 배터리 얼마나 남았어?"),
        ),
        Routine(
            "work_mode",
            "Work mode. Silencing distractions." to "업무 모드를 시작합니다.",
            listOf("오늘 일정 알려줘", "할 일 목록 알려줘", "볼륨 30퍼센트로 설정해"),
        ),
    ).associateBy { it.name }

    private val aliases = mapOf(
        "굿모닝" to "good_morning", "좋은 아침" to "good_morning", "아침" to "good_morning", "morning" to "good_morning",
        "굿나잇" to "good_night", "잘자" to "good_night", "취침" to "good_night", "night" to "good_night",
        "외출" to "leaving_home", "출근" to "leaving_home", "나가기" to "leaving_home", "leaving" to "leaving_home",
        "업무" to "work_mode", "작업" to "work_mode", "work" to "work_mode", "집중" to "work_mode",
    )

    suspend fun find(raw: String): Routine? {
        val key = raw.trim().lowercase()
        val builtInKey = aliases[key] ?: aliases.entries.firstOrNull { key.contains(it.key) }?.value ?: key.replace(' ', '_')
        builtIn[builtInKey]?.let { return it }
        val custom = repo.all().firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
            ?: repo.all().firstOrNull { raw.contains(it.name, ignoreCase = true) }
        return custom?.let { Routine(it.name, null, it.steps.lines().filter { l -> l.isNotBlank() }) }
    }

    /** A user-defined routine mentioned with a trigger word ("공부 루틴 실행"). Built-ins are matched by the parser. */
    suspend fun matchSpoken(text: String): String? {
        if (!Regex("루틴|routine|모드|시작|실행").containsMatchIn(text)) return null
        return repo.all().firstOrNull { text.contains(it.name, ignoreCase = true) }?.name
    }

    suspend fun names(): List<String> = builtIn.keys.toList() + repo.all().map { it.name }
}

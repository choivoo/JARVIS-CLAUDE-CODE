package com.friday.assistant.command

enum class Answer { YES, NO, UNKNOWN }

/** Understands a spoken Korean or English yes/no for confirmation prompts. */
object YesNoParser {
    fun parse(input: String): Answer {
        val t = input.lowercase().replace(" ", "")
        if (t.isEmpty()) return Answer.UNKNOWN
        val no = Regex("(아니|아냐|취소|하지마|그만|싫어|말아|노$|^no|cancel|stop|don't)")
        val yes = Regex("(^네|^예|^응|^어$|^그래|좋아|해줘|해주세요|걸어|보내|맞아|확인|^yes|^yeah|^yep|^ok|sure|please|go ahead)")
        return when {
            no.containsMatchIn(t) -> Answer.NO
            yes.containsMatchIn(t) -> Answer.YES
            else -> Answer.UNKNOWN
        }
    }
}

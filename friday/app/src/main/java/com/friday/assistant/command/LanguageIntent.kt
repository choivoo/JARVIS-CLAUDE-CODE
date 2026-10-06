package com.friday.assistant.command

/** "한국어로 말해줘" / "영어로 말해줘" and one-off "…한국어로 알려줘". Spoken language only; subtitles are always Korean. */
sealed class LanguageIntent {
    data object SwitchToKorean : LanguageIntent()
    data object SwitchToEnglish : LanguageIntent()
    /** Answer this one request in Korean; [request] is the sentence without the language words. */
    data class KoreanOnce(val request: String) : LanguageIntent()

    companion object {
        private val koWord = Regex("한국어로|한국말로|우리말로")
        private val enWord = Regex("영어로|영어말로")
        private val verbs = Regex("(말해|말씀|대답|답해|답변|얘기|이야기|해|해줘|해주세요|할래|하자)")

        fun parse(input: String): LanguageIntent? {
            val t = input.trim().trimEnd('.', '!', '?')
            val n = t.replace(" ", "")
            if (koWord.containsMatchIn(n)) {
                val rest = t.replace(koWord, " ").replace(Regex("\\s+"), " ").trim()
                val restN = rest.replace(" ", "").replace(Regex("(앞으로|이제부터|이제|지금부터|계속|좀|가끔|다시|한번|주세요|해줘|해주세요|말해줘|말해|대답해줘|대답해|대답|말씀해줘|말씀해주세요|얘기해줘|이야기해줘)"), "")
                // nothing but "speak Korean": a mode switch
                if (restN.isEmpty() && verbs.containsMatchIn(n)) return SwitchToKorean
                if (rest.isNotEmpty()) return KoreanOnce(rest)
            }
            if (enWord.containsMatchIn(n) && verbs.containsMatchIn(n)) {
                val restN = t.replace(enWord, " ").replace(" ", "").replace(Regex("(앞으로|이제부터|이제|지금부터|계속|다시|한번|주세요|말해줘|말해|대답해줘|대답해|해줘|해주세요|말씀해줘|얘기해줘)"), "")
                if (restN.isEmpty()) return SwitchToEnglish
            }
            return null
        }
    }
}

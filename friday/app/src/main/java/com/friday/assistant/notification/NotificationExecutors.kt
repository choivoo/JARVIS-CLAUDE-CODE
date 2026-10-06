package com.friday.assistant.notification

import com.friday.assistant.command.CardKind
import com.friday.assistant.command.Command
import com.friday.assistant.command.CommandExecutor
import com.friday.assistant.command.CommandResult
import com.friday.assistant.command.CommandType
import com.friday.assistant.command.ConfirmableExecutor
import com.friday.assistant.command.InfoCard
import com.friday.assistant.util.EnglishNumbers

/**
 * Notification commands. Contents are only ever shown on screen / read on the device and are never returned as
 * AI `data`, so private text does not leave the phone. Non-English text appears in the subtitle (the English voice
 * would mispronounce it); short Latin-script text is spoken.
 */
class NotificationExecutors(private val source: NotificationSource) {
    fun all(): Map<CommandType, CommandExecutor> = mapOf(
        CommandType.GET_NOTIFICATION_COUNT to CommandExecutor { _, _ -> count() },
        CommandType.GET_NOTIFICATIONS to CommandExecutor { _, _ -> list() },
        CommandType.READ_LATEST_NOTIFICATION to CommandExecutor { _, _ -> readLatest(null) },
        CommandType.READ_NOTIFICATIONS_FROM_APP to CommandExecutor { c, _ -> readLatest(c.param("target")) },
        CommandType.OPEN_NOTIFICATION to CommandExecutor { c, _ -> open(c.param("target")) },
        CommandType.DISMISS_NOTIFICATION to object : ConfirmableExecutor() {
            override suspend fun prepare(command: Command) = prepareDismiss(command.param("target"))
            override suspend fun perform(command: Command) = performDismiss(command)
        },
    )

    private fun count(): CommandResult {
        val n = source.recent(MAX_LIST).size
        return if (n == 0) CommandResult.ok("You have no recent notifications.", "최근 알림이 없습니다.")
        else CommandResult.ok(
            "You have ${EnglishNumbers.words(n)} recent notification${if (n == 1) "" else "s"}.",
            "최근 알림이 ${n}개 있습니다.",
        )
    }

    private fun list(): CommandResult {
        val items = source.recent(5)
        if (items.isEmpty()) return CommandResult.ok("You have no recent notifications.", "최근 알림이 없습니다.")
        val apps = items.map { it.appLabel }.distinct()
        val appsEn = apps.map { speakable(it) }.distinct().take(3).joinToString(", ")
        return CommandResult.ok(
            "You have ${EnglishNumbers.words(items.size)} recent notification${if (items.size == 1) "" else "s"}, from $appsEn.",
            "최근 알림 ${items.size}개: " + items.take(3).joinToString(" · ") { "${it.appLabel}: ${it.title.ifBlank { it.text }.take(24)}" },
            card = InfoCard(CardKind.NOTIFICATION, "알림 ${items.size}", items.map { "${it.appLabel} — ${it.title.ifBlank { it.text }.take(40)}" }),
        )
    }

    private fun filter(app: String?): List<NotificationInfo> {
        val all = source.recent(MAX_LIST)
        if (app == null) return all
        val q = norm(app)
        return all.filter { norm(it.appLabel).contains(q) || norm(it.packageName).contains(q) }
    }

    private fun readLatest(app: String?): CommandResult {
        val n = filter(app).firstOrNull()
            ?: return if (app == null) CommandResult.ok("You have no recent notifications.", "최근 알림이 없습니다.")
            else CommandResult.ok("I don't see any recent notifications from that app.", "해당 앱의 최근 알림이 없습니다.")
        val body = listOf(n.title, n.text).filter { it.isNotBlank() }.joinToString(": ")
        val speech = if (isMostlyLatin(body)) "${speakable(n.appLabel)}. ${body.take(200)}" else "Latest notification from ${speakable(n.appLabel)}. I've put the text on screen."
        return CommandResult.ok(
            speech, "${n.appLabel} — $body".take(220),
            card = InfoCard(CardKind.NOTIFICATION, n.appLabel, listOf(n.title, n.text.take(120)).filter { it.isNotBlank() }),
        )
    }

    private fun open(app: String?): CommandResult {
        val n = filter(app).firstOrNull() ?: return CommandResult.failed("There is no such notification.", "열 알림이 없습니다.")
        return if (source.open(n.key)) CommandResult.ok("Opening it.", "알림을 엽니다.")
        else CommandResult.failed("Android didn't let me open that notification.", "이 알림은 열 수 없습니다.")
    }

    private fun prepareDismiss(app: String?): CommandResult {
        val n = filter(app).firstOrNull() ?: return CommandResult.failed("There is no such notification.", "지울 알림이 없습니다.")
        return CommandResult.confirm(
            "Dismiss the latest notification from ${speakable(n.appLabel)}?",
            "${n.appLabel}의 최근 알림을 지울까요?",
            Command(CommandType.DISMISS_NOTIFICATION, mapOf("key" to n.key, "target" to n.appLabel)),
        )
    }

    private fun performDismiss(c: Command): CommandResult {
        val key = c.param("key") ?: return CommandResult.failed("I lost track of that notification.", "알림을 찾지 못했습니다.")
        return if (source.dismiss(key)) CommandResult.ok("Dismissed.", "알림을 지웠습니다.")
        else CommandResult.failed("I couldn't dismiss it. Android may not allow that for this notification.", "이 알림은 지울 수 없습니다.")
    }

    companion object {
        const val MAX_LIST = 20
        private fun norm(s: String) = s.lowercase().replace(Regex("[\\s._-]"), "")
        /** App names in Hangul are spoken as "that app"-style English where possible. */
        fun speakable(label: String) = if (isMostlyLatin(label)) label else when (norm(label)) {
            "카카오톡" -> "KakaoTalk"; "유튜브" -> "YouTube"; "메시지" -> "Messages"; "전화" -> "Phone"; else -> "an app"
        }
        fun isMostlyLatin(s: String): Boolean {
            val letters = s.filter { it.isLetter() }
            return letters.isNotEmpty() && letters.count { it.code < 0x250 } * 100 / letters.length >= 80
        }
    }
}

package com.friday.assistant.brief

import com.friday.assistant.calendar.CalendarExecutors
import com.friday.assistant.calendar.CalendarProvider
import com.friday.assistant.command.CardKind
import com.friday.assistant.command.CommandExecutor
import com.friday.assistant.command.CommandResult
import com.friday.assistant.command.CommandType
import com.friday.assistant.command.InfoCard
import com.friday.assistant.device.DeviceStatusProvider
import com.friday.assistant.notification.NotificationSource
import com.friday.assistant.util.EnglishNumbers
import com.friday.assistant.weather.WeatherReport
import java.time.LocalDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Builds the morning and evening briefs from whatever is available. Each source is optional and independent:
 * a missing permission, no network or an error simply leaves that item out; nothing is ever invented.
 */
class BriefingComposer(
    private val clock: () -> LocalDateTime,
    private val weather: suspend () -> WeatherReport?,
    private val calendar: CalendarProvider?,
    private val notifications: NotificationSource?,
    private val device: DeviceStatusProvider?,
    private val perSourceTimeoutMs: Long = 6_000,
) {
    fun executors(): Map<CommandType, CommandExecutor> = mapOf(
        CommandType.MORNING_BRIEF to CommandExecutor { _, _ -> morning() },
        CommandType.EVENING_BRIEF to CommandExecutor { _, _ -> evening() },
    )

    private class Part(val en: String, val ko: String)

    private suspend fun <T> guarded(block: suspend () -> T?): T? = try {
        withTimeoutOrNull(perSourceTimeoutMs) { block() }
    } catch (e: CancellationException) { throw e } catch (e: Exception) { null }

    suspend fun morning(): CommandResult {
        val now = clock()
        val parts = mutableListOf<Part>()
        val greeting = when (now.hour) { in 5..11 -> "Good morning" to "좋은 아침입니다"; in 12..17 -> "Good afternoon" to "안녕하세요"; else -> "Good evening" to "안녕하세요" }
        parts += Part("${greeting.first}. It's ${timeEn(now)}.", "${greeting.second}. 현재 ${hourKo(now)}입니다.")

        guarded { weather() }?.let { w ->
            val t = Math.round(w.tempC).toInt()
            val d = w.today
            val rain = d?.rainChancePct
            val rainEn = when { rain == null -> ""; rain >= 50 -> " with a good chance of rain today"; rain >= 20 -> " with a chance of rain today"; else -> "" }
            val rainKo = when { rain == null -> ""; rain >= 20 -> ", 오늘 강수확률 ${rain}%"; else -> "" }
            parts += Part("The temperature is ${EnglishNumbers.words(t)} degrees$rainEn.", "기온은 ${t}°C${rainKo}입니다.")
        }
        calendarPart(now.toLocalDate().atStartOfDay(), now.toLocalDate().plusDays(1).atStartOfDay(), "today", "오늘", false, now)?.let { parts += it }
        batteryPart()?.let { parts += it }
        notificationPart()?.let { parts += it }
        return compose("MORNING", parts)
    }

    suspend fun evening(): CommandResult {
        val now = clock()
        val parts = mutableListOf<Part>()
        parts += Part("Here's your wrap-up. It's ${timeEn(now)}.", "오늘 정리입니다. 현재 ${hourKo(now)}입니다.")
        val today = now.toLocalDate()
        calendarPart(now, today.plusDays(1).atStartOfDay(), "left today", "오늘 남은", true, now)?.let { parts += it }
        guarded { calendar?.takeIf { it.canRead() }?.events(today.plusDays(1).atStartOfDay(), today.plusDays(2).atStartOfDay()) }?.let { ev ->
            val first = ev.firstOrNull()
            if (first == null) parts += Part("Tomorrow is clear.", "내일은 일정이 없습니다.")
            else parts += Part("Tomorrow your first event is at ${CalendarExecutors.timeWords(first)}.", "내일 첫 일정은 ${CalendarExecutors.timeKo(first)} ${first.title}입니다.")
        }
        guarded { weather() }?.tomorrow?.let { t ->
            val rain = t.rainChancePct
            parts += Part(
                "Tomorrow looks like ${t.condition}, high ${EnglishNumbers.words(Math.round(t.maxC).toInt())}${if (rain != null && rain >= 20) ", with ${EnglishNumbers.words(rain)} percent chance of rain" else ""}.",
                "내일은 최고 ${Math.round(t.maxC)}°C${if (rain != null && rain >= 20) ", 강수확률 ${rain}%" else ""}입니다.",
            )
        }
        batteryPart()?.let { parts += it }
        notificationPart()?.let { parts += it }
        return compose("EVENING", parts)
    }

    private fun compose(kind: String, parts: List<Part>): CommandResult {
        val en = parts.joinToString(" ") { it.en }
        val ko = parts.joinToString(" ") { it.ko }
        return CommandResult.ok(
            en, ko,
            card = InfoCard(CardKind.DEVICE, if (kind == "MORNING") "아침 브리핑" else "저녁 정리", parts.map { it.ko }),
            contextNote = "${kind.lowercase()} brief given",
        )
    }

    private suspend fun calendarPart(from: LocalDateTime, to: LocalDateTime, wordEn: String, wordKo: String, upcomingOnly: Boolean, now: LocalDateTime): Part? {
        val events = guarded { calendar?.takeIf { it.canRead() }?.events(from, to) } ?: return null
        val list = if (upcomingOnly) events.filter { !it.end.isBefore(now) } else events
        if (list.isEmpty()) return Part("You have no events $wordEn.", "$wordKo 일정이 없습니다.")
        val n = list.size
        return Part(
            "You have ${EnglishNumbers.words(n)} event${if (n == 1) "" else "s"} $wordEn${if (n > 0) ", the first at ${CalendarExecutors.timeWords(list.first())}" else ""}.",
            "$wordKo 일정이 ${n}개 있습니다. 첫 일정은 ${CalendarExecutors.timeKo(list.first())} ${list.first().title}.",
        )
    }

    private fun batteryPart(): Part? {
        val s = guardedSync { device?.read() } ?: return null
        val p = s.batteryPercent ?: return null
        return Part("Your battery is at ${EnglishNumbers.words(p)} percent.", "배터리는 ${p}%입니다.")
    }

    private fun notificationPart(): Part? {
        val src = notifications?.takeIf { it.accessEnabled() } ?: return null
        val n = guardedSync { src.recent(20).size } ?: return null
        if (n == 0) return null
        return Part("You have ${EnglishNumbers.words(n)} recent notification${if (n == 1) "" else "s"}.", "최근 알림이 ${n}개 있습니다.")
    }

    private fun <T> guardedSync(block: () -> T?): T? = try { block() } catch (e: Exception) { null }

    private fun timeEn(t: LocalDateTime): String {
        val h = if (t.hour % 12 == 0) 12 else t.hour % 12
        return "$h:${"%02d".format(t.minute)} ${if (t.hour < 12) "AM" else "PM"}"
    }
    private fun hourKo(t: LocalDateTime) = CalendarExecutors.hourKo(t)
}

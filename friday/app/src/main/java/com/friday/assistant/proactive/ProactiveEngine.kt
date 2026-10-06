package com.friday.assistant.proactive

import com.friday.assistant.calendar.CalendarExecutors
import com.friday.assistant.calendar.CalendarProvider
import com.friday.assistant.device.DeviceStatusProvider
import com.friday.assistant.settings.FridaySettings
import com.friday.assistant.util.EnglishNumbers
import com.friday.assistant.weather.WeatherReport
import java.time.LocalDateTime

enum class AlertKind { UPCOMING_EVENT, LOW_BATTERY, WEATHER_WARNING, IMPORTANT_NOTIFICATION }

data class ProactiveAlert(val kind: AlertKind, val speech: String, val subtitle: String, val id: String)

/** One reason for FRIDAY to speak up. Sources are cheap, run only on explicit triggers and never poll. */
fun interface ProactiveSource { suspend fun check(now: LocalDateTime): ProactiveAlert? }

class LowBatterySource(private val device: DeviceStatusProvider, private val threshold: Int = 15) : ProactiveSource {
    override suspend fun check(now: LocalDateTime): ProactiveAlert? {
        val s = device.read()
        val p = s.batteryPercent ?: return null
        if (s.charging == true || p > threshold) return null
        return ProactiveAlert(AlertKind.LOW_BATTERY, "Your battery is low, at ${EnglishNumbers.words(p)} percent.", "배터리가 부족합니다 (${p}%).", "battery")
    }
}

class UpcomingEventSource(private val calendar: CalendarProvider, private val leadMinutes: Long = 15) : ProactiveSource {
    override suspend fun check(now: LocalDateTime): ProactiveAlert? {
        if (!calendar.canRead()) return null
        val e = calendar.events(now, now.plusMinutes(leadMinutes)).firstOrNull { !it.allDay && !it.start.isBefore(now) } ?: return null
        val mins = java.time.Duration.between(now, e.start).toMinutes()
        return ProactiveAlert(
            AlertKind.UPCOMING_EVENT, "You have an event in ${EnglishNumbers.words(mins.toInt())} minutes, at ${CalendarExecutors.timeWords(e)}.",
            "${mins}분 뒤 일정: ${e.title}", "event:${e.start}:${e.title.hashCode()}",
        )
    }
}

class WeatherWarningSource(private val weather: suspend () -> WeatherReport?) : ProactiveSource {
    override suspend fun check(now: LocalDateTime): ProactiveAlert? {
        val w = weather() ?: return null
        val rain = w.today?.rainChancePct ?: return null
        if (rain < 70 || now.hour > 20) return null
        return ProactiveAlert(AlertKind.WEATHER_WARNING, "Heads up, ${EnglishNumbers.words(rain)} percent chance of rain today.", "오늘 비 올 확률이 ${rain}%입니다. 우산을 챙기세요.", "weather:${now.toLocalDate()}")
    }
}

/**
 * Collects alerts from the sources the user enabled and suppresses repeats. Delivery (a notification, optionally
 * speech) is the caller's job. It is driven by explicit events (battery-low broadcast, a coarse timer while the
 * Background Assistant is on) so it adds no background polling of its own.
 */
class ProactiveEngine(
    private val settings: () -> FridaySettings,
    private val sources: Map<AlertKind, ProactiveSource>,
) {
    private val delivered = HashSet<String>()

    suspend fun evaluate(now: LocalDateTime, only: Set<AlertKind>? = null): List<ProactiveAlert> {
        val s = settings()
        val enabled = buildSet {
            if (s.proactiveLowBattery) add(AlertKind.LOW_BATTERY)
            if (s.proactiveUpcomingEvent) add(AlertKind.UPCOMING_EVENT)
            if (s.proactiveWeather) add(AlertKind.WEATHER_WARNING)
        }
        val out = ArrayList<ProactiveAlert>()
        for ((kind, source) in sources) {
            if (kind !in enabled || (only != null && kind !in only)) continue
            val alert = runCatching { source.check(now) }.getOrNull() ?: continue
            if (delivered.add(alert.id)) out += alert
        }
        return out
    }

    fun reset() = delivered.clear()
}

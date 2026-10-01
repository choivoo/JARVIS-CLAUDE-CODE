package com.jarvis.assistant.command.commands

import com.jarvis.assistant.ai.AiAction
import com.jarvis.assistant.command.ActionResult
import com.jarvis.assistant.command.Command
import com.jarvis.assistant.data.model.SettingKeys
import com.jarvis.assistant.data.repository.SettingsRepository
import com.jarvis.assistant.weather.LocationProvider
import com.jarvis.assistant.weather.WeatherException
import com.jarvis.assistant.weather.WeatherProvider
import com.jarvis.assistant.weather.WeatherReport
import com.jarvis.assistant.weather.WeatherText

class WeatherCommand(
    private val provider: WeatherProvider,
    private val location: LocationProvider,
    private val settingsRepo: SettingsRepository,
) : Command {
    override val types = listOf("WEATHER", "GET_WEATHER", "FORECAST")

    override suspend fun execute(action: AiAction): ActionResult {
        val day = if (action.param("day")?.lowercase() in setOf("tomorrow", "내일")) "tomorrow" else "today"
        val city = action.param("city")?.takeIf { !it.equals("current", true) && it != "현재 위치" }
        val settings = settingsRepo.current()
        try {
            val report: WeatherReport = when {
                city != null -> provider.forCity(city)
                else -> {
                    val fix = location.currentOrNull()
                    when {
                        fix != null -> {
                            settingsRepo.putAll(
                                SettingKeys.LAST_LAT to fix.latitude.toString(),
                                SettingKeys.LAST_LON to fix.longitude.toString(),
                            )
                            provider.forPoint(fix.latitude, fix.longitude, "your location")
                        }
                        settings.lastLat != null && settings.lastLon != null ->
                            provider.forPoint(settings.lastLat, settings.lastLon, "your last known location")
                        else -> provider.forCity(settings.weatherCity)
                    }
                }
            }
            val (en, ko) = WeatherText.summary(report, day)
            return ActionResult(true, en, ko, WeatherText.data(report, day), narrate = true)
        } catch (e: WeatherException) {
            return when (e.kind) {
                WeatherException.Kind.NETWORK -> ActionResult.fail(
                    "Connection unavailable.",
                    "인터넷 연결을 사용할 수 없습니다.",
                )
                WeatherException.Kind.NOT_FOUND -> ActionResult.fail(
                    "I couldn't find that location.",
                    "해당 위치를 찾을 수 없습니다.",
                )
                WeatherException.Kind.BAD_RESPONSE -> ActionResult.fail(
                    "The weather service gave me an unusable answer.",
                    "날씨 서비스에서 올바른 응답을 받지 못했습니다.",
                )
            }
        }
    }
}

package com.jarvis.assistant.command.commands

import com.jarvis.assistant.data.model.SettingKeys
import com.jarvis.assistant.data.repository.SettingsRepository
import com.jarvis.assistant.weather.GeoPoint
import com.jarvis.assistant.weather.LocationProvider
import com.jarvis.assistant.weather.WeatherProvider

/** Named city -> live location -> last known location -> default city. */
class PlaceResolver(
    private val weather: WeatherProvider,
    private val location: LocationProvider,
    private val settingsRepo: SettingsRepository,
) {
    suspend fun resolve(city: String?): GeoPoint {
        val named = city?.takeIf { it.isNotBlank() && !it.equals("current", true) && it != "현재 위치" }
        if (named != null) return weather.locate(com.jarvis.assistant.weather.OpenMeteoWeatherProvider.normalizeCity(named))
        location.currentOrNull()?.let {
            settingsRepo.putAll(SettingKeys.LAST_LAT to it.latitude.toString(), SettingKeys.LAST_LON to it.longitude.toString())
            return GeoPoint(it.latitude, it.longitude, "your location")
        }
        val s = settingsRepo.current()
        if (s.lastLat != null && s.lastLon != null) return GeoPoint(s.lastLat, s.lastLon, "your last known location")
        return weather.locate(s.weatherCity)
    }
}

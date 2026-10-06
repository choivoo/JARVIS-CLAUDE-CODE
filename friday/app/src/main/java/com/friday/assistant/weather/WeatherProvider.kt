package com.friday.assistant.weather

import org.json.JSONObject

data class DayForecast(val maxC: Double, val minC: Double, val rainChancePct: Int?, val condition: String)

data class WeatherReport(
    val place: String,
    val tempC: Double,
    val feelsLikeC: Double,
    val condition: String,
    val today: DayForecast?,
    val tomorrow: DayForecast?,
) {
    /** Compact text handed to the AI as the tool result. */
    fun toToolText(): String = buildString {
        append("place=$place; now=${tempC.fmt()}C feels_like=${feelsLikeC.fmt()}C, $condition")
        today?.let { append("; today max=${it.maxC.fmt()}C min=${it.minC.fmt()}C rain_chance=${it.rainChancePct ?: "n/a"}% ${it.condition}") }
        tomorrow?.let { append("; tomorrow max=${it.maxC.fmt()}C min=${it.minC.fmt()}C rain_chance=${it.rainChancePct ?: "n/a"}% ${it.condition}") }
    }

    private fun Double.fmt() = Math.round(this).toString()
}

interface WeatherProvider {
    /** [city] null means: use the device location if available, else the default city. */
    suspend fun fetch(city: String?, latitude: Double? = null, longitude: Double? = null): WeatherReport
}

class WeatherException(message: String, val noInternet: Boolean = false) : Exception(message)

/** Pure JSON parsing for Open-Meteo, unit-tested without the network. */
object WeatherParser {
    fun parseGeocoding(json: String): Triple<String, Double, Double>? {
        val results = JSONObject(json).optJSONArray("results") ?: return null
        if (results.length() == 0) return null
        val r = results.getJSONObject(0)
        return Triple(r.optString("name", ""), r.getDouble("latitude"), r.getDouble("longitude"))
    }

    fun parseForecast(json: String, place: String): WeatherReport {
        val root = JSONObject(json)
        val cur = root.getJSONObject("current")
        val daily = root.optJSONObject("daily")
        fun day(i: Int): DayForecast? {
            val d = daily ?: return null
            val max = d.optJSONArray("temperature_2m_max") ?: return null
            val min = d.optJSONArray("temperature_2m_min") ?: return null
            if (i >= max.length() || i >= min.length()) return null
            val pop = d.optJSONArray("precipitation_probability_max")
            val codes = d.optJSONArray("weather_code")
            return DayForecast(
                maxC = max.getDouble(i),
                minC = min.getDouble(i),
                rainChancePct = if (pop != null && i < pop.length() && !pop.isNull(i)) pop.getInt(i) else null,
                condition = describe(if (codes != null && i < codes.length()) codes.getInt(i) else -1),
            )
        }
        return WeatherReport(
            place = place,
            tempC = cur.getDouble("temperature_2m"),
            feelsLikeC = cur.optDouble("apparent_temperature", cur.getDouble("temperature_2m")),
            condition = describe(cur.optInt("weather_code", -1)),
            today = day(0),
            tomorrow = day(1),
        )
    }

    /** WMO weather interpretation codes. */
    fun describe(code: Int): String = when (code) {
        0 -> "clear sky"
        1, 2 -> "partly cloudy"
        3 -> "overcast"
        45, 48 -> "fog"
        51, 53, 55, 56, 57 -> "drizzle"
        61, 63, 65, 66, 67 -> "rain"
        71, 73, 75, 77 -> "snow"
        80, 81, 82 -> "rain showers"
        85, 86 -> "snow showers"
        95, 96, 99 -> "thunderstorm"
        else -> "unknown conditions"
    }
}

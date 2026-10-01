package com.jarvis.assistant.weather

data class GeoPoint(val lat: Double, val lon: Double, val label: String)

data class DayForecast(
    val code: Int,
    val maxC: Double,
    val minC: Double,
    val precipProbability: Int?,
)

data class WeatherReport(
    val place: String,
    val currentC: Double,
    val feelsLikeC: Double,
    val currentCode: Int,
    val humidity: Int?,
    val windKmh: Double?,
    val today: DayForecast,
    val tomorrow: DayForecast?,
)

class WeatherException(val kind: Kind, message: String) : Exception(message) {
    enum class Kind { NETWORK, NOT_FOUND, BAD_RESPONSE }
}

/** Weather abstraction; [OpenMeteoWeatherProvider] needs no API key. */
interface WeatherProvider {
    @Throws(WeatherException::class)
    suspend fun forCity(city: String): WeatherReport

    @Throws(WeatherException::class)
    suspend fun forPoint(lat: Double, lon: Double, label: String): WeatherReport
}

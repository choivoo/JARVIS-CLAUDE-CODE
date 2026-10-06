package com.friday.assistant.weather

import com.friday.assistant.util.Http
import java.io.IOException
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Open-Meteo: free, no API key. */
class OpenMeteoProvider(private val client: OkHttpClient = Http.client) : WeatherProvider {

    override suspend fun fetch(city: String?, latitude: Double?, longitude: Double?): WeatherReport {
        var lat = latitude
        var lon = longitude
        var place = city ?: "your location"
        if (lat == null || lon == null) {
            val name = city ?: throw WeatherException("No city")
            val geo = get(
                "https://geocoding-api.open-meteo.com/v1/search?name=${URLEncoder.encode(name, "UTF-8")}&count=1&language=en&format=json",
            )
            val hit = WeatherParser.parseGeocoding(geo) ?: throw WeatherException("City not found: $name")
            place = hit.first.ifBlank { name }
            lat = hit.second
            lon = hit.third
        }
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,apparent_temperature,weather_code" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=auto&forecast_days=2"
        return WeatherParser.parseForecast(get(url), place)
    }

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) throw WeatherException("Weather service error ${r.code}")
                r.body?.string().orEmpty()
            }
        } catch (e: IOException) {
            throw WeatherException("No connection", noInternet = true)
        }
    }
}

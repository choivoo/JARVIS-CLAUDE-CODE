package com.jarvis.assistant.weather

import com.jarvis.assistant.util.Http
import com.jarvis.assistant.util.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

data class DailyOutlook(
    val date: String,
    val code: Int,
    val maxC: Double,
    val minC: Double,
    val rainChance: Int?,
    val uvMax: Double?,
    val sunrise: String?,
    val sunset: String?,
)

data class AirQuality(val usAqi: Int, val pm25: Double?, val pm10: Double?)

/** Open-Meteo extras beyond the basic forecast: multi-day outlook, sun times, UV and air quality. */
class EnvironmentProvider {
    suspend fun outlook(lat: Double, lon: Double, days: Int): List<DailyOutlook> {
        val url = "https://api.open-meteo.com/v1/forecast".toHttpUrl().newBuilder()
            .addQueryParameter("latitude", lat.toString())
            .addQueryParameter("longitude", lon.toString())
            .addQueryParameter(
                "daily",
                "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,uv_index_max,sunrise,sunset",
            )
            .addQueryParameter("timezone", "auto")
            .addQueryParameter("forecast_days", days.coerceIn(1, 14).toString())
            .build()
        return try {
            parseOutlook(JSONObject(get(url.toString())))
        } catch (e: JSONException) {
            throw WeatherException(WeatherException.Kind.BAD_RESPONSE, "Bad outlook response")
        }
    }

    suspend fun airQuality(lat: Double, lon: Double): AirQuality {
        val url = "https://air-quality-api.open-meteo.com/v1/air-quality".toHttpUrl().newBuilder()
            .addQueryParameter("latitude", lat.toString())
            .addQueryParameter("longitude", lon.toString())
            .addQueryParameter("current", "us_aqi,pm10,pm2_5")
            .build()
        return try {
            parseAir(JSONObject(get(url.toString())))
        } catch (e: JSONException) {
            throw WeatherException(WeatherException.Kind.BAD_RESPONSE, "Bad air quality response")
        }
    }

    internal fun parseOutlook(json: JSONObject): List<DailyOutlook> {
        val d = json.getJSONObject("daily")
        val dates = d.getJSONArray("time")
        fun str(name: String, i: Int) = d.optJSONArray(name)?.takeIf { i < it.length() && !it.isNull(i) }?.getString(i)
        fun dbl(name: String, i: Int) = d.optJSONArray(name)?.takeIf { i < it.length() && !it.isNull(i) }?.getDouble(i)
        return (0 until dates.length()).map { i ->
            DailyOutlook(
                date = dates.getString(i),
                code = d.getJSONArray("weather_code").getInt(i),
                maxC = d.getJSONArray("temperature_2m_max").getDouble(i),
                minC = d.getJSONArray("temperature_2m_min").getDouble(i),
                rainChance = dbl("precipitation_probability_max", i)?.toInt(),
                uvMax = dbl("uv_index_max", i),
                sunrise = str("sunrise", i)?.substringAfter('T'),
                sunset = str("sunset", i)?.substringAfter('T'),
            )
        }
    }

    internal fun parseAir(json: JSONObject): AirQuality {
        val c = json.getJSONObject("current")
        return AirQuality(
            usAqi = c.getInt("us_aqi"),
            pm25 = if (c.isNull("pm2_5")) null else c.getDouble("pm2_5"),
            pm10 = if (c.isNull("pm10")) null else c.getDouble("pm10"),
        )
    }

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        try {
            Http.client.await(Request.Builder().url(url).header("User-Agent", "JARVIS-Android/1.2").build()).use { r ->
                if (!r.isSuccessful) throw WeatherException(WeatherException.Kind.BAD_RESPONSE, "HTTP ${r.code}")
                r.body?.string().orEmpty()
            }
        } catch (e: IOException) {
            throw WeatherException(WeatherException.Kind.NETWORK, "Service unreachable")
        }
    }

    companion object {
        fun aqiCategory(aqi: Int): Pair<String, String> = when {
            aqi <= 50 -> "good" to "좋음"
            aqi <= 100 -> "moderate" to "보통"
            aqi <= 150 -> "unhealthy for sensitive groups" to "민감군 나쁨"
            aqi <= 200 -> "unhealthy" to "나쁨"
            aqi <= 300 -> "very unhealthy" to "매우 나쁨"
            else -> "hazardous" to "위험"
        }

        fun uvCategory(uv: Double): Pair<String, String> = when {
            uv < 3 -> "low" to "낮음"
            uv < 6 -> "moderate" to "보통"
            uv < 8 -> "high" to "높음"
            uv < 11 -> "very high" to "매우 높음"
            else -> "extreme" to "위험"
        }
    }
}

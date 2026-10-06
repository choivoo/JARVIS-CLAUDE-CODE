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

/** Keyless forecast data from open-meteo.com. */
class OpenMeteoWeatherProvider : WeatherProvider {

    override suspend fun forCity(city: String): WeatherReport {
        val point = locate(city)
        return forPoint(point.lat, point.lon, point.label)
    }

    override suspend fun locate(city: String): GeoPoint {
        val url = "https://geocoding-api.open-meteo.com/v1/search".toHttpUrl().newBuilder()
            .addQueryParameter("name", normalizeCity(city))
            .addQueryParameter("count", "1")
            .addQueryParameter("language", "en")
            .build()
        val body = get(url.toString())
        try {
            val hit = JSONObject(body).optJSONArray("results")?.optJSONObject(0)
                ?: throw WeatherException(WeatherException.Kind.NOT_FOUND, "Unknown city")
            return GeoPoint(hit.getDouble("latitude"), hit.getDouble("longitude"), hit.optString("name", city))
        } catch (e: JSONException) {
            throw WeatherException(WeatherException.Kind.BAD_RESPONSE, "Bad geocoding response")
        }
    }

    override suspend fun forPoint(lat: Double, lon: Double, label: String): WeatherReport {
        val url = "https://api.open-meteo.com/v1/forecast".toHttpUrl().newBuilder()
            .addQueryParameter("latitude", lat.toString())
            .addQueryParameter("longitude", lon.toString())
            .addQueryParameter(
                "current",
                "temperature_2m,apparent_temperature,weather_code,relative_humidity_2m,wind_speed_10m",
            )
            .addQueryParameter(
                "daily",
                "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max",
            )
            .addQueryParameter("timezone", "auto")
            .addQueryParameter("forecast_days", "2")
            .build()
        val body = get(url.toString())
        return try {
            parse(JSONObject(body), label)
        } catch (e: JSONException) {
            throw WeatherException(WeatherException.Kind.BAD_RESPONSE, "Bad forecast response")
        }
    }

    internal fun parse(json: JSONObject, label: String): WeatherReport {
        val current = json.getJSONObject("current")
        val daily = json.getJSONObject("daily")
        fun day(i: Int): DayForecast? {
            val codes = daily.getJSONArray("weather_code")
            if (i >= codes.length()) return null
            val prob = daily.optJSONArray("precipitation_probability_max")
            return DayForecast(
                code = codes.getInt(i),
                maxC = daily.getJSONArray("temperature_2m_max").getDouble(i),
                minC = daily.getJSONArray("temperature_2m_min").getDouble(i),
                precipProbability = if (prob != null && i < prob.length() && !prob.isNull(i)) prob.getInt(i) else null,
            )
        }
        return WeatherReport(
            place = label,
            currentC = current.getDouble("temperature_2m"),
            feelsLikeC = current.optDouble("apparent_temperature", current.getDouble("temperature_2m")),
            currentCode = current.getInt("weather_code"),
            humidity = if (current.has("relative_humidity_2m")) current.optInt("relative_humidity_2m") else null,
            windKmh = if (current.has("wind_speed_10m")) current.optDouble("wind_speed_10m") else null,
            today = day(0) ?: throw JSONException("no daily data"),
            tomorrow = day(1),
        )
    }

    internal suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        try {
            Http.client.await(Request.Builder().url(url).header("User-Agent", "JARVIS-Android/1.0").build())
                .use { response ->
                    if (!response.isSuccessful) {
                        throw WeatherException(WeatherException.Kind.BAD_RESPONSE, "HTTP ${response.code}")
                    }
                    response.body?.string().orEmpty()
                }
        } catch (e: IOException) {
            throw WeatherException(WeatherException.Kind.NETWORK, "Weather service unreachable")
        }
    }

    companion object {
        private val koreanCities = mapOf(
            "서울" to "Seoul", "부산" to "Busan", "인천" to "Incheon", "대구" to "Daegu", "대전" to "Daejeon",
            "광주" to "Gwangju", "울산" to "Ulsan", "수원" to "Suwon", "제주" to "Jeju", "세종" to "Sejong",
            "성남" to "Seongnam", "고양" to "Goyang", "용인" to "Yongin", "창원" to "Changwon", "청주" to "Cheongju",
            "전주" to "Jeonju", "포항" to "Pohang", "춘천" to "Chuncheon", "강릉" to "Gangneung",
            "도쿄" to "Tokyo", "오사카" to "Osaka", "뉴욕" to "New York", "런던" to "London", "파리" to "Paris",
            "베이징" to "Beijing", "상하이" to "Shanghai", "로스앤젤레스" to "Los Angeles", "시드니" to "Sydney",
        )

        fun normalizeCity(raw: String): String {
            val trimmed = raw.trim().removeSuffix("시").trim()
            return koreanCities[trimmed] ?: koreanCities[raw.trim()] ?: raw.trim()
        }
    }
}

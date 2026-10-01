package com.jarvis.assistant

import com.jarvis.assistant.weather.OpenMeteoWeatherProvider
import com.jarvis.assistant.weather.WeatherText
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenMeteoParseTest {
    private val sample = """
    {"current":{"temperature_2m":18.4,"apparent_temperature":17.1,"weather_code":2,"relative_humidity_2m":55,"wind_speed_10m":9.0},
     "daily":{"weather_code":[2,61],"temperature_2m_max":[22.0,19.5],"temperature_2m_min":[12.0,11.0],"precipitation_probability_max":[20,70]}}
    """.trimIndent()

    @Test
    fun parsesReport() {
        val report = OpenMeteoWeatherProvider().parse(JSONObject(sample), "Seoul")
        assertEquals(18.4, report.currentC, 0.001)
        assertEquals(20, report.today.precipProbability)
        assertEquals(70, report.tomorrow?.precipProbability)
    }

    @Test
    fun rendersBilingualSummary() {
        val report = OpenMeteoWeatherProvider().parse(JSONObject(sample), "Seoul")
        val (en, ko) = WeatherText.summary(report, "today")
        assertTrue(en.contains("18 degrees") && en.contains("Seoul"))
        assertTrue(ko.contains("18도") && ko.contains("구름 조금"))
        val (enT, koT) = WeatherText.summary(report, "tomorrow")
        assertTrue(enT.contains("Tomorrow") && enT.contains("70 percent"))
        assertTrue(koT.contains("내일"))
    }

    @Test
    fun normalizesKoreanCityNames() {
        assertEquals("Seoul", OpenMeteoWeatherProvider.normalizeCity("서울"))
        assertEquals("Busan", OpenMeteoWeatherProvider.normalizeCity("부산시"))
        assertEquals("Tokyo", OpenMeteoWeatherProvider.normalizeCity("Tokyo"))
    }
}

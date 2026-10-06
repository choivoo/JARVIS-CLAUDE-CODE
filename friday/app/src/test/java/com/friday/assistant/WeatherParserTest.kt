package com.friday.assistant

import com.friday.assistant.weather.WeatherParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WeatherParserTest {
    private val forecast = """
        {"current":{"temperature_2m":18.4,"apparent_temperature":16.9,"weather_code":2},
         "daily":{"weather_code":[61,0],"temperature_2m_max":[22.1,24.0],"temperature_2m_min":[12.2,13.5],
                  "precipitation_probability_max":[70,10]}}
    """.trimIndent()

    @Test fun parsesForecast() {
        val r = WeatherParser.parseForecast(forecast, "Seoul")
        assertEquals("Seoul", r.place)
        assertEquals(18.4, r.tempC, 0.01)
        assertEquals(16.9, r.feelsLikeC, 0.01)
        assertEquals("partly cloudy", r.condition)
        assertEquals(70, r.today?.rainChancePct)
        assertEquals("rain", r.today?.condition)
        assertEquals("clear sky", r.tomorrow?.condition)
        assertEquals(24.0, r.tomorrow!!.maxC, 0.01)
    }

    @Test fun toolTextContainsEssentials() {
        val t = WeatherParser.parseForecast(forecast, "Seoul").toToolText()
        assertEquals(true, t.contains("Seoul") && t.contains("rain_chance=70%") && t.contains("feels_like=17C"))
    }

    @Test fun missingDailyIsTolerated() {
        val r = WeatherParser.parseForecast("""{"current":{"temperature_2m":5.0,"weather_code":71}}""", "X")
        assertNull(r.today)
        assertEquals("snow", r.condition)
        assertEquals(5.0, r.feelsLikeC, 0.01)
    }

    @Test fun geocoding() {
        val hit = WeatherParser.parseGeocoding("""{"results":[{"name":"Seoul","latitude":37.57,"longitude":126.98}]}""")
        assertNotNull(hit); assertEquals("Seoul", hit!!.first); assertEquals(37.57, hit.second, 0.001)
        assertNull(WeatherParser.parseGeocoding("""{"generationtime_ms":0.2}"""))
        assertNull(WeatherParser.parseGeocoding("""{"results":[]}"""))
    }

    @Test fun weatherCodes() {
        assertEquals("thunderstorm", WeatherParser.describe(95))
        assertEquals("fog", WeatherParser.describe(45))
        assertEquals("unknown conditions", WeatherParser.describe(1234))
    }
}

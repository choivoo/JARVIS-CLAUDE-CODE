package com.jarvis.assistant.weather

import kotlin.math.roundToInt

/** Human readable English + Korean renderings of a [WeatherReport] (WMO weather codes). */
object WeatherText {
    fun conditionEn(code: Int): String = when (code) {
        0 -> "clear"
        1 -> "mostly clear"
        2 -> "partly cloudy"
        3 -> "overcast"
        45, 48 -> "foggy"
        51, 53, 55 -> "drizzling"
        56, 57 -> "freezing drizzle"
        61 -> "lightly raining"
        63 -> "raining"
        65 -> "raining heavily"
        66, 67 -> "freezing rain"
        71, 73, 75, 77 -> "snowing"
        80, 81, 82 -> "showery"
        85, 86 -> "snow showers"
        95 -> "thunderstorms"
        96, 99 -> "thunderstorms with hail"
        else -> "unsettled"
    }

    fun conditionKo(code: Int): String = when (code) {
        0 -> "맑음"
        1 -> "대체로 맑음"
        2 -> "구름 조금"
        3 -> "흐림"
        45, 48 -> "안개"
        51, 53, 55 -> "이슬비"
        56, 57 -> "어는 이슬비"
        61 -> "약한 비"
        63 -> "비"
        65 -> "강한 비"
        66, 67 -> "어는 비"
        71, 73, 75, 77 -> "눈"
        80, 81, 82 -> "소나기"
        85, 86 -> "눈 소나기"
        95 -> "뇌우"
        96, 99 -> "우박을 동반한 뇌우"
        else -> "변덕스러운 날씨"
    }

    private fun t(v: Double) = v.roundToInt()

    fun data(report: WeatherReport, day: String): String = buildString {
        append("Place: ${report.place}\n")
        append("Now: ${t(report.currentC)}C, feels like ${t(report.feelsLikeC)}C, ${conditionEn(report.currentCode)}")
        report.humidity?.let { append(", humidity $it%") }
        report.windKmh?.let { append(", wind ${t(it)} km/h") }
        append("\nToday: high ${t(report.today.maxC)}C, low ${t(report.today.minC)}C, ${conditionEn(report.today.code)}")
        report.today.precipProbability?.let { append(", rain chance $it%") }
        report.tomorrow?.let {
            append("\nTomorrow: high ${t(it.maxC)}C, low ${t(it.minC)}C, ${conditionEn(it.code)}")
            it.precipProbability?.let { p -> append(", rain chance $p%") }
        }
        append("\nUser asked about: $day")
    }

    /** Returns (english, korean). */
    fun summary(report: WeatherReport, day: String): Pair<String, String> {
        if (day == "tomorrow" && report.tomorrow != null) {
            val f = report.tomorrow
            val rainEn = f.precipProbability?.let { " The chance of rain is $it percent." }.orEmpty()
            val rainKo = f.precipProbability?.let { " 강수 확률은 ${it}%입니다." }.orEmpty()
            return Pair(
                "Tomorrow in ${report.place} will be ${conditionEn(f.code)}, between ${t(f.minC)} and ${t(f.maxC)} degrees.$rainEn",
                "내일 ${report.place}은(는) ${conditionKo(f.code)}, 기온은 ${t(f.minC)}도에서 ${t(f.maxC)}도입니다.$rainKo",
            )
        }
        val rainEn = report.today.precipProbability?.let { " The chance of rain today is $it percent." }.orEmpty()
        val rainKo = report.today.precipProbability?.let { " 오늘 강수 확률은 ${it}%입니다." }.orEmpty()
        return Pair(
            "It's ${t(report.currentC)} degrees and ${conditionEn(report.currentCode)} in ${report.place}, feeling like ${t(report.feelsLikeC)}.$rainEn",
            "현재 ${report.place}은(는) ${t(report.currentC)}도, ${conditionKo(report.currentCode)}이며 체감온도는 ${t(report.feelsLikeC)}도입니다.$rainKo",
        )
    }
}

package com.jarvis.assistant

import com.jarvis.assistant.ai.AiAction
import com.jarvis.assistant.audio.UiSounds
import com.jarvis.assistant.command.LocalIntentParser
import com.jarvis.assistant.command.commands.CalculateCommand
import com.jarvis.assistant.command.commands.CompassCommand
import com.jarvis.assistant.command.commands.CryptoPriceCommand
import com.jarvis.assistant.command.commands.CurrencyCommand
import com.jarvis.assistant.command.commands.NewsCommand
import com.jarvis.assistant.command.commands.ReminderSetCommand
import com.jarvis.assistant.command.commands.WorldTimeCommand
import com.jarvis.assistant.data.model.VoiceStyle
import com.jarvis.assistant.data.repository.TaskRepository
import com.jarvis.assistant.tools.ExpressionEvaluator
import com.jarvis.assistant.tools.ExpressionException
import com.jarvis.assistant.tools.StopwatchEngine
import com.jarvis.assistant.tools.UnitConverter
import com.jarvis.assistant.tts.PcmAudio
import com.jarvis.assistant.tts.VoiceFx
import com.jarvis.assistant.weather.EnvironmentProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class V12ToolsTest {
    // ---- calculator ----
    private fun calc(s: String) = ExpressionEvaluator.evaluate(CalculateCommand.percentOf(s))

    @Test
    fun arithmeticAndPrecedence() {
        assertEquals(14.0, calc("2 + 3 * 4"), 1e-9)
        assertEquals(20.0, calc("(2 + 3) * 4"), 1e-9)
        assertEquals(8.0, calc("2 ^ 3"), 1e-9)
        assertEquals(512.0, calc("2 ^ 3 ^ 2"), 1e-9)
        assertEquals(-5.0, calc("-5"), 1e-9)
        assertEquals(3.5, calc("7 / 2"), 1e-9)
        assertEquals(1.0, calc("10 % 3"), 1e-9)
    }

    @Test
    fun spokenOperatorsAndFunctions() {
        assertEquals(8.0, calc("3 더하기 5"), 1e-9)
        assertEquals(96.0, calc("12 곱하기 8"), 1e-9)
        assertEquals(4.0, calc("루트 16"), 1e-9)
        assertEquals(10.0, calc("20% of 50"), 1e-9)
        assertEquals(1.0, calc("sin(90)"), 1e-9)
        assertEquals(Math.PI, calc("pi"), 1e-9)
        assertEquals("0.3", ExpressionEvaluator.format(0.1 + 0.2))
        assertEquals("42", ExpressionEvaluator.format(42.0))
    }

    @Test
    fun badInputIsRejectedSafely() {
        for (bad in listOf("1 / 0", "2 +", "(3", "foo(2)", "sqrt(-1)", "1 2 3", "system(1)")) {
            try {
                ExpressionEvaluator.evaluate(bad)
                throw AssertionError("should reject: $bad")
            } catch (e: ExpressionException) {
                // expected
            }
        }
    }

    // ---- units ----
    @Test
    fun unitConversions() {
        assertEquals(6.2137, UnitConverter.convert(10.0, "km", "mile")!!.value, 1e-3)
        assertEquals(212.0, UnitConverter.convert(100.0, "c", "f")!!.value, 1e-9)
        assertEquals(0.0, UnitConverter.convert(273.15, "k", "c")!!.value, 1e-9)
        assertEquals(1000.0, UnitConverter.convert(1.0, "킬로그램", "그램")!!.value, 1e-9)
        assertEquals(1.0, UnitConverter.convert(1000.0, "mb", "gb")!!.value, 1e-9)
        assertNull(UnitConverter.convert(1.0, "km", "kg"))
        assertNull(UnitConverter.convert(1.0, "parsec", "km"))
    }

    // ---- stopwatch ----
    @Test
    fun stopwatchTracksTimeAndLaps() {
        var now = 1_000L
        val sw = StopwatchEngine { now }
        assertNull(sw.stop())
        assertTrue(sw.start())
        now += 5_000
        assertEquals(5_000L, sw.lap())
        now += 2_500
        assertEquals(7_500L, sw.stop())
        assertEquals(7_500L, sw.elapsedMs())
        assertEquals(1, sw.lapCount())
        assertEquals("1 minute 5 seconds", StopwatchEngine.speakEn(65_000))
        assertEquals("1분 5초", StopwatchEngine.speakKo(65_000))
        sw.reset()
        assertEquals(0L, sw.elapsedMs())
    }

    // ---- voice FX ----
    private fun tone(hz: Double, seconds: Double, rate: Int = 22_050, amp: Double = 0.4): PcmAudio {
        val n = (seconds * rate).toInt()
        val data = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = (sin(2 * PI * hz * i / rate) * amp * 32767).toInt()
            data[i * 2] = (v and 0xFF).toByte()
            data[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return PcmAudio(data, rate, 1)
    }

    private fun peak(a: PcmAudio): Int {
        var m = 0
        for (i in 0 until a.data.size / 2) {
            val s = ((a.data[i * 2 + 1].toInt() shl 8) or (a.data[i * 2].toInt() and 0xFF)).toShort().toInt()
            m = maxOf(m, abs(s))
        }
        return m
    }

    @Test
    fun voiceFxKeepsAudioValidAndBounded() {
        val input = tone(180.0, 0.6)
        for (style in listOf(VoiceStyle.JARVIS, VoiceStyle.ROBOTIC)) {
            val out = VoiceFx.process(input, style)
            assertEquals(1, out.channels)
            assertEquals(input.sampleRate, out.sampleRate)
            assertTrue("longer by lead-in and tail", out.data.size > input.data.size)
            val p = peak(out)
            assertTrue("peak $p within range", p in 20_000..32_000)
        }
    }

    @Test
    fun naturalStyleIsUntouchedAndSilenceStaysSilent() {
        val input = tone(220.0, 0.2)
        assertTrue(VoiceFx.process(input, VoiceStyle.NATURAL) === input)
        val silence = PcmAudio(ByteArray(4_000), 22_050, 1)
        assertEquals(0, peak(VoiceFx.process(silence, VoiceStyle.JARVIS)))
    }

    /** Gain in dB of a filter at [hz], measured by running a sine through it. */
    private fun gainDb(make: (Float) -> VoiceFx.Biquad, hz: Double, rate: Float = 22_050f): Double {
        val n = 22_050
        val x = FloatArray(n) { sin(2 * PI * hz * it / rate).toFloat() }
        val y = make(rate).run(x)
        fun rms(a: FloatArray) = Math.sqrt(a.drop(n / 2).map { it.toDouble() * it }.average())
        return 20 * Math.log10(rms(y) / rms(x))
    }

    @Test
    fun jarvisEqCurveIsWhatWeDesigned() {
        val lowShelf = { r: Float -> VoiceFx.Biquad.lowShelf(r, 190f, 5f) }
        assertEquals(5.0, gainDb(lowShelf, 40.0), 0.6)          // full shelf gain well below the corner
        assertEquals(0.0, gainDb(lowShelf, 4_000.0), 0.4)       // untouched high end
        val presence = { r: Float -> VoiceFx.Biquad.peaking(r, 2800f, 1f, 3f) }
        assertEquals(3.0, gainDb(presence, 2_800.0), 0.3)
        assertEquals(0.0, gainDb(presence, 150.0), 0.5)
        val highPass = { r: Float -> VoiceFx.Biquad.highPass(r, 75f, 0.707f) }
        assertTrue(gainDb(highPass, 20.0) < -15.0)              // rumble removed
        assertEquals(0.0, gainDb(highPass, 1_000.0), 0.2)
    }

    // ---- ui sounds ----
    @Test
    fun uiSoundsAreAudibleAndShort() {
        for (k in UiSounds.Kind.values()) {
            val s = UiSounds.synth(k)
            assertTrue("$k not empty", s.isNotEmpty())
            assertTrue("$k shorter than 1.5 s", s.size < UiSounds.RATE * 3 / 2)
            assertTrue("$k audible", s.any { abs(it.toInt()) > 800 })
        }
    }

    // ---- reminders ----
    @Test
    fun reminderTimes() {
        val now = LocalDateTime.of(2026, 10, 2, 15, 0)
        fun t(vararg p: Pair<String, String>) = ReminderSetCommand.triggerTime(AiAction("REMINDER_SET", p.toMap()), now)
        assertEquals(now.plusMinutes(30), t("minutes_from_now" to "30"))
        assertEquals(now.plusHours(2), t("hours_from_now" to "2"))
        assertEquals(LocalDateTime.of(2026, 10, 2, 18, 30), t("hour" to "18", "minutes" to "30"))
        assertEquals(LocalDateTime.of(2026, 10, 3, 9, 0), t("hour" to "9", "minutes" to "0")) // already passed today -> tomorrow
        assertEquals(LocalDateTime.of(2026, 12, 25, 8, 0), t("hour" to "8", "date" to "2026-12-25"))
        assertNull(t("text" to "nothing"))
        assertNull(t("hour" to "25"))
    }

    // ---- parsing external data ----
    @Test
    fun newsTitlesAreExtractedAndCleaned() {
        val xml = """<rss><channel><title>Feed</title>
            <item><title><![CDATA[정부, 새 정책 발표 - 연합뉴스]]></title></item>
            <item><title>A &amp; B 소식 - KBS</title></item></channel></rss>"""
        assertEquals(listOf("정부, 새 정책 발표", "A & B 소식"), NewsCommand.parseTitles(xml))
    }

    @Test
    fun airQualityAndOutlookParsing() {
        val env = EnvironmentProvider()
        val air = env.parseAir(JSONObject("""{"current":{"us_aqi":62,"pm10":30.1,"pm2_5":18.4}}"""))
        assertEquals(62, air.usAqi)
        assertEquals("moderate", EnvironmentProvider.aqiCategory(air.usAqi).first)
        assertEquals("unhealthy", EnvironmentProvider.aqiCategory(170).first)
        val days = env.parseOutlook(
            JSONObject(
                """{"daily":{"time":["2026-10-02","2026-10-03"],"weather_code":[0,61],
                "temperature_2m_max":[21.5,19.0],"temperature_2m_min":[11.0,10.0],
                "precipitation_probability_max":[10,80],"uv_index_max":[4.2,2.0],
                "sunrise":["2026-10-02T06:12","2026-10-03T06:13"],"sunset":["2026-10-02T17:55","2026-10-03T17:53"]}}""",
            ),
        )
        assertEquals(2, days.size)
        assertEquals("06:12", days[0].sunrise)
        assertEquals(80, days[1].rainChance)
        assertEquals("moderate", EnvironmentProvider.uvCategory(days[0].uvMax!!).first)
    }

    @Test
    fun smallLookups() {
        assertEquals("KRW", CurrencyCommand.code("원"))
        assertEquals("USD", CurrencyCommand.code("달러"))
        assertEquals("ethereum", CryptoPriceCommand.coinId("이더리움"))
        assertEquals("north" to "북", CompassCommand.headingName(5))
        assertEquals("west" to "서", CompassCommand.headingName(268))
        assertNotNull(WorldTimeCommand.zoneFor("뉴욕"))
        assertNotNull(WorldTimeCommand.zoneFor("Asia/Seoul"))
        assertNull(WorldTimeCommand.zoneFor("Narnia"))
        assertEquals("shopping", TaskRepository.normalize("장보기"))
        assertEquals("todo", TaskRepository.normalize(""))
    }

    // ---- Korean parser v1.2 ----
    private fun parse(text: String) = LocalIntentParser.parse(text)?.action

    @Test
    fun parserCoversTheNewCommandSet() {
        assertEquals("CALCULATE", parse("12 곱하기 8 계산해줘")?.type)
        assertEquals("ROLL_DICE", parse("주사위 굴려줘")?.type)
        assertEquals("FLIP_COIN", parse("동전 던져줘")?.type)
        assertEquals("TELL_JOKE", parse("농담 해줘")?.type)
        assertEquals("BREATHE", parse("호흡 명상 시작해줘")?.type)
        assertEquals("GET_DATE", parse("오늘 며칠이야")?.type)
        assertEquals("NEWS", parse("오늘 뉴스 알려줘")?.type)
        assertEquals("CRYPTO_PRICE", parse("비트코인 시세 알려줘")?.type)
        assertEquals("AIR_QUALITY", parse("미세먼지 알려줘")?.type)
        assertEquals("UV_INDEX", parse("자외선 지수 알려줘")?.type)
        assertEquals("SUN_TIMES", parse("일몰 시간 알려줘")?.type)
        assertEquals("WEATHER_WEEK", parse("이번 주 날씨 알려줘")?.type)
        assertEquals("WHERE_AM_I", parse("내 위치 알려줘")?.type)
        assertEquals("CALENDAR_TODAY", parse("오늘 일정 알려줘")?.type)
        assertEquals("CALENDAR_TOMORROW", parse("내일 일정 알려줘")?.type)
        assertEquals("DAILY_BRIEFING", parse("브리핑 해줘")?.type)
        assertEquals("STATUS_REPORT", parse("시스템 점검해줘")?.type)
        assertEquals("FLASHLIGHT_SOS", parse("SOS 신호 보내줘")?.type)
        assertEquals("FIND_PHONE", parse("내 폰 어디 있어")?.type)
        assertEquals("MUTE", parse("음소거 해줘")?.type)
        assertEquals("UNMUTE", parse("음소거 해제해줘")?.type)
        assertEquals("RINGER_VIBRATE", parse("진동 모드로 바꿔줘")?.type)
        assertEquals("STORAGE_INFO", parse("저장 공간 얼마나 남았어")?.type)
        assertEquals("AMBIENT_LIGHT", parse("주변 밝기 알려줘")?.type)
        assertEquals("COMPASS", parse("나침반 켜줘")?.type)
        assertEquals("REPEAT", parse("다시 말해줘")?.type)
        assertEquals("HELP", parse("뭘 할 수 있어")?.type)
    }

    @Test
    fun parserExtractsParameters() {
        val rem = parse("30분 뒤에 빨래 알려줘")
        assertEquals("REMINDER_SET", rem?.type)
        assertEquals("30", rem?.param("minutes_from_now"))
        assertEquals("빨래", rem?.param("text"))
        val shop = parse("우유를 장보기 목록에 추가해줘")
        assertEquals("TASK_ADD", shop?.type)
        assertEquals("shopping", shop?.param("list"))
        assertEquals("우유", shop?.param("text"))
        val cur = parse("100달러는 얼마야 원으로 환율")
        assertEquals("CONVERT_CURRENCY", cur?.type)
        assertEquals("USD", CurrencyCommand.code(cur?.param("from") ?: ""))
        val br = parse("화면 밝기 70퍼센트로 해줘")
        assertEquals("BRIGHTNESS_SET", br?.type)
        assertEquals("70", br?.param("level"))
        assertEquals("RUN_ROUTINE", parse("굿모닝 루틴 시작")?.type)
        assertEquals("good_morning".let { "굿모닝" }, parse("굿모닝 루틴 시작")?.param("name"))
        val call = parse("엄마에게 전화해줘")
        assertEquals("CALL", call?.type)
        assertEquals("엄마", call?.param("name"))
        val song = parse("아이유 노래 틀어줘")
        assertEquals("PLAY_MUSIC", song?.type)
        assertEquals("아이유", song?.param("query"))
        assertEquals("SEARCH_SITE", parse("네이버에서 맛집 검색해줘")?.type)
    }
}

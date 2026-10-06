package com.jarvis.assistant.command.commands

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.jarvis.assistant.ai.AiAction
import com.jarvis.assistant.command.ActionResult
import com.jarvis.assistant.command.Command
import com.jarvis.assistant.tools.ExpressionEvaluator
import com.jarvis.assistant.tools.ExpressionException
import com.jarvis.assistant.tools.StopwatchEngine
import com.jarvis.assistant.tools.UnitConverter
import com.jarvis.assistant.util.Http
import com.jarvis.assistant.util.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

// ---------------------------------------------------------------------------------------------
// Date & time
// ---------------------------------------------------------------------------------------------

class GetDateCommand : Command {
    override val types = listOf("GET_DATE", "TODAY", "GET_DAY")
    override val executesBeforeSpeech = true

    override suspend fun execute(action: AiAction): ActionResult {
        val offset = action.num("offset_days")?.toLong() ?: 0L
        val d = LocalDate.now().plusDays(offset)
        val en = d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        val month = d.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        val ko = d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.KOREAN)
        val label = when (offset) { 0L -> "Today is"; 1L -> "Tomorrow is"; -1L -> "Yesterday was"; else -> "That is" }
        val labelKo = when (offset) { 0L -> "오늘은"; 1L -> "내일은"; -1L -> "어제는"; else -> "해당 날짜는" }
        return ActionResult.ok(
            "$label $en, $month ${d.dayOfMonth}.",
            "$labelKo ${d.year}년 ${d.monthValue}월 ${d.dayOfMonth}일 $ko 입니다.",
        )
    }
}

class WorldTimeCommand : Command {
    override val types = listOf("WORLD_TIME", "TIME_IN", "TIMEZONE")
    override val executesBeforeSpeech = true

    override suspend fun execute(action: AiAction): ActionResult {
        val city = action.param("city") ?: return ActionResult.fail("Which city?", "어느 도시의 시간을 알려드릴까요?")
        val zone = zoneFor(city) ?: return ActionResult.fail(
            "I don't know the time zone for that place.", "해당 지역의 시간대를 알 수 없습니다.",
        )
        val there = ZonedDateTime.now(zone)
        val here = ZonedDateTime.now()
        val diffHours = (there.offset.totalSeconds - here.offset.totalSeconds) / 3600.0
        val diffEn = if (diffHours == 0.0) "the same as here" else "%.1f hours %s".format(kotlin.math.abs(diffHours), if (diffHours > 0) "ahead" else "behind")
        val diffKo = if (diffHours == 0.0) "여기와 같습니다" else "%.1f시간 %s".format(kotlin.math.abs(diffHours), if (diffHours > 0) "빠릅니다" else "느립니다")
        return ActionResult.ok(
            "It's ${there.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))} in ${city.trim()}, $diffEn.",
            "${city.trim()}은(는) 지금 ${there.hour}시 ${there.minute}분이며, $diffKo.",
        )
    }

    companion object {
        private val zones = mapOf(
            "seoul" to "Asia/Seoul", "서울" to "Asia/Seoul", "한국" to "Asia/Seoul", "korea" to "Asia/Seoul",
            "tokyo" to "Asia/Tokyo", "도쿄" to "Asia/Tokyo", "일본" to "Asia/Tokyo", "japan" to "Asia/Tokyo",
            "beijing" to "Asia/Shanghai", "shanghai" to "Asia/Shanghai", "베이징" to "Asia/Shanghai", "상하이" to "Asia/Shanghai", "중국" to "Asia/Shanghai",
            "hong kong" to "Asia/Hong_Kong", "홍콩" to "Asia/Hong_Kong", "singapore" to "Asia/Singapore", "싱가포르" to "Asia/Singapore",
            "bangkok" to "Asia/Bangkok", "방콕" to "Asia/Bangkok", "delhi" to "Asia/Kolkata", "델리" to "Asia/Kolkata", "인도" to "Asia/Kolkata",
            "dubai" to "Asia/Dubai", "두바이" to "Asia/Dubai", "moscow" to "Europe/Moscow", "모스크바" to "Europe/Moscow",
            "london" to "Europe/London", "런던" to "Europe/London", "영국" to "Europe/London", "paris" to "Europe/Paris", "파리" to "Europe/Paris",
            "berlin" to "Europe/Berlin", "베를린" to "Europe/Berlin", "rome" to "Europe/Rome", "로마" to "Europe/Rome", "madrid" to "Europe/Madrid", "마드리드" to "Europe/Madrid",
            "new york" to "America/New_York", "뉴욕" to "America/New_York", "chicago" to "America/Chicago", "시카고" to "America/Chicago",
            "los angeles" to "America/Los_Angeles", "la" to "America/Los_Angeles", "san francisco" to "America/Los_Angeles",
            "로스앤젤레스" to "America/Los_Angeles", "샌프란시스코" to "America/Los_Angeles", "toronto" to "America/Toronto", "토론토" to "America/Toronto",
            "sydney" to "Australia/Sydney", "시드니" to "Australia/Sydney", "auckland" to "Pacific/Auckland", "오클랜드" to "Pacific/Auckland",
            "utc" to "UTC", "gmt" to "UTC",
        )

        fun zoneFor(city: String): ZoneId? {
            val key = city.trim().lowercase().removeSuffix("시")
            zones[key]?.let { return ZoneId.of(it) }
            return runCatching { ZoneId.of(city.trim()) }.getOrNull()
        }
    }
}

class DaysUntilCommand : Command {
    override val types = listOf("DAYS_UNTIL", "COUNTDOWN_TO", "DAYS_BETWEEN")
    override val executesBeforeSpeech = true

    override suspend fun execute(action: AiAction): ActionResult {
        val today = LocalDate.now()
        val target = action.param("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: run {
                val m = action.num("month")?.toInt()
                val d = action.num("day")?.toInt()
                if (m != null && d != null) {
                    val thisYear = runCatching { LocalDate.of(today.year, m, d) }.getOrNull()
                    thisYear?.let { if (it.isBefore(today)) it.plusYears(1) else it }
                } else null
            }
            ?: return ActionResult.fail("Which date should I count to?", "어느 날짜까지 계산할까요?")
        val days = ChronoUnit.DAYS.between(today, target)
        val label = action.param("label")
        val en = when {
            days == 0L -> "That is today."
            days > 0 -> "$days day${if (days != 1L) "s" else ""} to go${label?.let { " until it" } ?: ""}."
            else -> "That was ${-days} day${if (days != -1L) "s" else ""} ago."
        }
        val ko = when {
            days == 0L -> "바로 오늘입니다."
            days > 0 -> "${days}일 남았습니다."
            else -> "${-days}일 지났습니다."
        }
        return ActionResult.ok(en, ko + (label?.let { " ($it)" } ?: ""))
    }
}

class StopwatchCommand(private val engine: StopwatchEngine = StopwatchEngine()) : Command {
    override val types = listOf("STOPWATCH_START", "STOPWATCH_STOP", "STOPWATCH_LAP", "STOPWATCH_RESET", "STOPWATCH_STATUS", "STOPWATCH")
    override val executesBeforeSpeech = true

    override suspend fun execute(action: AiAction): ActionResult = when (action.kind()) {
        "STOPWATCH_START" -> if (engine.start()) ActionResult.ok("Stopwatch started.", "스톱워치를 시작했습니다.")
        else ActionResult.ok("The stopwatch is already running.", "스톱워치가 이미 실행 중입니다.")
        "STOPWATCH_STOP" -> engine.stop()?.let {
            ActionResult.ok("Stopped at ${StopwatchEngine.speakEn(it)}.", "${StopwatchEngine.speakKo(it)}에 멈췄습니다.")
        } ?: ActionResult.ok("The stopwatch isn't running.", "스톱워치가 실행 중이 아닙니다.")
        "STOPWATCH_LAP" -> engine.lap()?.let {
            ActionResult.ok("Lap ${engine.lapCount()}: ${StopwatchEngine.speakEn(it)}.", "${engine.lapCount()}번째 랩: ${StopwatchEngine.speakKo(it)}")
        } ?: ActionResult.ok("The stopwatch isn't running.", "스톱워치가 실행 중이 아닙니다.")
        "STOPWATCH_RESET" -> {
            engine.reset()
            ActionResult.ok("Stopwatch reset.", "스톱워치를 초기화했습니다.")
        }
        else -> {
            val e = engine.elapsedMs()
            ActionResult.ok(
                "The stopwatch ${if (engine.running) "is at" else "shows"} ${StopwatchEngine.speakEn(e)}.",
                "스톱워치: ${StopwatchEngine.speakKo(e)}",
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Calculation & conversion
// ---------------------------------------------------------------------------------------------

class CalculateCommand : Command {
    override val types = listOf("CALCULATE", "CALC", "MATH", "COMPUTE")
    override val executesBeforeSpeech = true

    override suspend fun execute(action: AiAction): ActionResult {
        val raw = action.param("expression") ?: action.param("query") ?: return ActionResult.fail(
            "What should I calculate?", "무엇을 계산할까요?",
        )
        return try {
            val result = ExpressionEvaluator.format(ExpressionEvaluator.evaluate(percentOf(raw)))
            ActionResult.ok("The answer is $result.", "답은 ${result}입니다.")
        } catch (e: ExpressionException) {
            ActionResult.fail("I couldn't work that out.", "계산하지 못했습니다. 식을 다시 말씀해 주세요.")
        }
    }

    companion object {
        /** "20% of 50" -> "(20/100)*(50)". */
        fun percentOf(expr: String): String =
            Regex("(\\d+(?:\\.\\d+)?)\\s*%\\s*(?:of|의)\\s*(\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)
                .replace(expr) { "(${it.groupValues[1]}/100)*(${it.groupValues[2]})" }
    }
}

class ConvertUnitsCommand : Command {
    override val types = listOf("CONVERT_UNITS", "UNIT_CONVERT", "CONVERT")
    override val executesBeforeSpeech = true

    override suspend fun execute(action: AiAction): ActionResult {
        val value = action.num("value") ?: return ActionResult.fail("What value should I convert?", "변환할 값을 말씀해 주세요.")
        val from = action.param("from") ?: return ActionResult.fail("Convert from which unit?", "어떤 단위에서 변환할까요?")
        val to = action.param("to") ?: return ActionResult.fail("Convert to which unit?", "어떤 단위로 변환할까요?")
        val r = UnitConverter.convert(value, from, to) ?: return ActionResult.fail(
            "I can't convert between those units.", "해당 단위 간 변환은 지원하지 않습니다.",
        )
        val shown = ExpressionEvaluator.format(Math.round(r.value * 1000) / 1000.0)
        return ActionResult.ok(
            "${ExpressionEvaluator.format(value)} ${plural(r.fromName, value)} is $shown ${plural(r.toName, r.value)}.",
            "${ExpressionEvaluator.format(value)} $from 은(는) $shown $to 입니다.",
        )
    }
}

internal fun plural(name: String, v: Double): String {
    if (v == 1.0) return name
    return when {
        name == "foot" -> "feet"
        name in setOf("celsius", "fahrenheit", "kelvin", "pyeong") -> name
        name.contains(" per ") -> name.replaceFirst(" per ", "s per ")
        else -> name + "s"
    }
}

class CurrencyCommand : Command {
    override val types = listOf("CONVERT_CURRENCY", "CURRENCY", "EXCHANGE_RATE")

    override suspend fun execute(action: AiAction): ActionResult {
        val amount = action.num("amount") ?: 1.0
        val from = code(action.param("from") ?: "USD")
        val to = code(action.param("to") ?: "KRW")
        if (from == to) return ActionResult.ok("Those are the same currency.", "같은 통화입니다.")
        return try {
            val url = "https://api.frankfurter.dev/v1/latest".toHttpUrl().newBuilder()
                .addQueryParameter("base", from).addQueryParameter("symbols", to)
                .addQueryParameter("amount", amount.toString()).build()
            val body = withContext(Dispatchers.IO) {
                Http.client.await(Request.Builder().url(url).header("User-Agent", "JARVIS-Android/1.2").build()).use {
                    if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
                    it.body?.string().orEmpty()
                }
            }
            val value = JSONObject(body).getJSONObject("rates").getDouble(to)
            val shown = ExpressionEvaluator.format(Math.round(value * 100) / 100.0)
            val a = ExpressionEvaluator.format(amount)
            ActionResult.ok("$a $from is about $shown $to.", "$a $from 은(는) 약 $shown $to 입니다.")
        } catch (e: Exception) {
            ActionResult.fail("I couldn't fetch the exchange rate.", "환율 정보를 가져오지 못했습니다.")
        }
    }

    companion object {
        fun code(raw: String): String {
            val s = raw.trim().lowercase()
            return when {
                s in setOf("dollar", "dollars", "usd", "달러", "미국 달러", "$") -> "USD"
                s in setOf("won", "krw", "원", "한국 원") -> "KRW"
                s in setOf("yen", "jpy", "엔", "일본 엔") -> "JPY"
                s in setOf("euro", "euros", "eur", "유로") -> "EUR"
                s in setOf("pound", "pounds", "gbp", "파운드") -> "GBP"
                s in setOf("yuan", "cny", "rmb", "위안") -> "CNY"
                s in setOf("franc", "chf", "프랑") -> "CHF"
                s in setOf("aud", "호주 달러") -> "AUD"
                s in setOf("cad", "캐나다 달러") -> "CAD"
                else -> raw.trim().uppercase().take(3)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Randomness & small utilities
// ---------------------------------------------------------------------------------------------

private val rng = SecureRandom()

class RandomNumberCommand : Command {
    override val types = listOf("RANDOM_NUMBER", "RANDOM")
    override val executesBeforeSpeech = true

    override suspend fun execute(action: AiAction): ActionResult {
        val min = action.num("min")?.toInt() ?: 1
        val max = action.num("max")?.toInt() ?: 100
        if (max < min) return ActionResult.fail("The range doesn't make sense.", "범위가 올바르지 않습니다.")
        val n = min + rng.nextInt(max - min + 1)
        return ActionResult.ok("Your number is $n.", "숫자는 ${n}입니다.")
    }
}

class DiceCommand : Command {
    override val types = listOf("ROLL_DICE", "DICE")
    override val executesBeforeSpeech = true

    override suspend fun execute(action: AiAction): ActionResult {
        val sides = (action.num("sides")?.toInt() ?: 6).coerceIn(2, 1000)
        val count = (action.num("count")?.toInt() ?: 1).coerceIn(1, 10)
        val rolls = List(count) { 1 + rng.nextInt(sides) }
        val text = rolls.joinToString(", ")
        val total = if (count > 1) " Total ${rolls.sum()}." else ""
        return ActionResult.ok("Rolled: $text.$total", "주사위 결과: $text" + if (count > 1) " (합계 ${rolls.sum()})" else "")
    }
}

class CoinFlipCommand : Command {
    override val types = listOf("FLIP_COIN", "COIN_FLIP", "COIN_TOSS")
    override val executesBeforeSpeech = true

    override suspend fun execute(action: AiAction): ActionResult =
        if (rng.nextBoolean()) ActionResult.ok("Heads.", "앞면입니다.") else ActionResult.ok("Tails.", "뒷면입니다.")
}

class PickRandomCommand : Command {
    override val types = listOf("PICK_RANDOM", "CHOOSE", "DECIDE")
    override val executesBeforeSpeech = true

    override suspend fun execute(action: AiAction): ActionResult {
        val options = (action.param("options") ?: "").split('|', ',', '/').map { it.trim() }.filter { it.isNotEmpty() }
        if (options.size < 2) return ActionResult.fail("Give me at least two options.", "선택지를 두 개 이상 말씀해 주세요.")
        val pick = options[rng.nextInt(options.size)]
        return ActionResult.ok("I choose: $pick.", "저는 '$pick'을(를) 고르겠습니다.")
    }
}

/** Generates a strong password and puts it on the clipboard without displaying or speaking it. */
class PasswordCommand(private val context: Context) : Command {
    override val types = listOf("GENERATE_PASSWORD", "PASSWORD")
    override val executesBeforeSpeech = true

    override suspend fun execute(action: AiAction): ActionResult {
        val length = (action.num("length")?.toInt() ?: 16).coerceIn(8, 64)
        val pool = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%^&*-_"
        val password = String(CharArray(length) { pool[rng.nextInt(pool.length)] })
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("JARVIS password", password).apply {
            description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        })
        return ActionResult.ok(
            "A $length character password is on your clipboard. I won't read it aloud.",
            "${length}자 비밀번호를 클립보드에 복사했습니다. 보안상 화면에는 표시하지 않습니다.",
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Fun (offline)
// ---------------------------------------------------------------------------------------------

private fun <T> List<T>.pick(): T = this[rng.nextInt(size)]

class JokeCommand : Command {
    override val types = listOf("TELL_JOKE", "JOKE")
    override val executesBeforeSpeech = true
    override suspend fun execute(action: AiAction): ActionResult {
        val (en, ko) = JOKES.pick()
        return ActionResult.ok(en, ko)
    }

    companion object {
        val JOKES = listOf(
            "I would tell you a UDP joke, but you might not get it." to "UDP 농담을 해드리고 싶지만, 전달이 안 될 수도 있겠군요.",
            "There are only ten kinds of people: those who understand binary, and those who don't." to "세상엔 두 종류의 사람이 있죠. 이진수를 아는 사람과 모르는 사람.",
            "I tried to catch some fog earlier. I mist." to "아까 안개를 잡으려 했는데, 놓쳤습니다. 안개 'mist'하고요.",
            "Why do programmers prefer dark mode? Because light attracts bugs." to "프로그래머들이 다크 모드를 좋아하는 이유는, 빛이 버그를 끌어들이기 때문입니다.",
            "My battery and I have something in common. We both run low at the worst moments." to "저와 배터리의 공통점은, 가장 곤란한 순간에 부족해진다는 점이죠.",
            "I'd call you a taxi, but I'm afraid I only do sarcasm and spreadsheets." to "택시를 불러드리고 싶지만, 제 전문은 빈정거림과 스프레드시트라서요.",
            "A SQL query walks into a bar, sees two tables and asks: may I join you?" to "SQL 쿼리가 바에 들어가 두 테이블을 보고 말했죠. 합석해도 될까요?",
            "Why was the computer cold? It left its Windows open." to "컴퓨터가 추웠던 이유는 윈도우를 열어 두었기 때문입니다.",
        )
    }
}

class QuoteCommand : Command {
    override val types = listOf("QUOTE", "MOTIVATE", "INSPIRE")
    override val executesBeforeSpeech = true
    override suspend fun execute(action: AiAction): ActionResult {
        val (en, ko) = QUOTES.pick()
        return ActionResult.ok(en, ko)
    }

    companion object {
        val QUOTES = listOf(
            "The only way to do great work is to love what you do." to "위대한 일을 하는 유일한 방법은 그 일을 사랑하는 것입니다.",
            "Well begun is half done." to "시작이 반입니다.",
            "It always seems impossible until it's done." to "모든 일은 해내기 전까지는 불가능해 보입니다.",
            "The best way to predict the future is to build it." to "미래를 예측하는 가장 좋은 방법은 직접 만드는 것입니다.",
            "Fall seven times, stand up eight." to "일곱 번 넘어져도 여덟 번 일어나십시오.",
            "Small steps every day add up to great distances." to "매일의 작은 걸음이 먼 거리를 만듭니다.",
            "Make it work, make it right, make it fast." to "먼저 동작하게 하고, 올바르게 하고, 그다음 빠르게 하십시오.",
            "Discipline is choosing between what you want now and what you want most." to "절제란 지금 원하는 것과 가장 원하는 것 사이에서 선택하는 일입니다.",
        )
    }
}

class FunFactCommand : Command {
    override val types = listOf("FUN_FACT", "TRIVIA")
    override val executesBeforeSpeech = true
    override suspend fun execute(action: AiAction): ActionResult {
        val (en, ko) = FACTS.pick()
        return ActionResult.ok(en, ko)
    }

    companion object {
        val FACTS = listOf(
            "Honey never spoils. Archaeologists have found edible honey in ancient Egyptian tombs." to "꿀은 상하지 않습니다. 고대 이집트 무덤에서 먹을 수 있는 꿀이 발견되었죠.",
            "A day on Venus is longer than its year." to "금성의 하루는 금성의 1년보다 깁니다.",
            "Octopuses have three hearts and blue blood." to "문어는 심장이 세 개이고 피가 파란색입니다.",
            "Bananas are slightly radioactive because of their potassium." to "바나나는 칼륨 때문에 아주 약한 방사능을 띱니다.",
            "The Eiffel Tower grows about fifteen centimetres taller in summer heat." to "에펠탑은 여름 열기로 약 15센티미터 늘어납니다.",
            "Light from the Sun takes about eight minutes to reach the Earth." to "태양빛이 지구에 닿기까지 약 8분이 걸립니다.",
            "Hangul, the Korean alphabet, was created in 1443 and published in 1446." to "한글은 1443년에 창제되어 1446년에 반포되었습니다.",
            "Your phone has more computing power than the computers that guided the Apollo missions." to "당신의 휴대폰은 아폴로 계획의 유도 컴퓨터보다 훨씬 강력합니다.",
        )
    }
}

class EightBallCommand : Command {
    override val types = listOf("ASK_8BALL", "EIGHT_BALL", "FORTUNE")
    override val executesBeforeSpeech = true
    override suspend fun execute(action: AiAction): ActionResult {
        val (en, ko) = ANSWERS.pick()
        return ActionResult.ok(en, ko)
    }

    companion object {
        val ANSWERS = listOf(
            "It is certain." to "확실합니다.", "Without a doubt." to "의심의 여지가 없습니다.",
            "Signs point to yes." to "긍정적인 징조가 보입니다.", "Ask again later." to "나중에 다시 물어보십시오.",
            "I wouldn't count on it." to "기대하지 않으시는 편이 좋겠습니다.", "My sources say no." to "제 정보로는 아니라고 합니다.",
            "Very doubtful." to "매우 의심스럽습니다.", "Yes, definitely." to "네, 틀림없습니다.",
            "Cannot predict now." to "지금은 예측할 수 없습니다.", "Outlook good." to "전망이 좋습니다.",
        )
    }
}

package com.jarvis.assistant.command.commands

import android.content.Context
import android.content.Intent
import android.location.Geocoder
import com.jarvis.assistant.ai.AiAction
import com.jarvis.assistant.command.ActionResult
import com.jarvis.assistant.command.ActivityLauncher
import com.jarvis.assistant.command.Command
import com.jarvis.assistant.tools.ExpressionEvaluator
import com.jarvis.assistant.util.Http
import com.jarvis.assistant.util.Perms
import com.jarvis.assistant.util.await
import com.jarvis.assistant.weather.EnvironmentProvider
import com.jarvis.assistant.weather.LocationProvider
import com.jarvis.assistant.weather.WeatherException
import com.jarvis.assistant.weather.WeatherText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

private fun weatherFailure(e: WeatherException): ActionResult = when (e.kind) {
    WeatherException.Kind.NETWORK -> ActionResult.fail("Connection unavailable.", "인터넷 연결을 사용할 수 없습니다.")
    WeatherException.Kind.NOT_FOUND -> ActionResult.fail("I couldn't find that location.", "해당 위치를 찾을 수 없습니다.")
    WeatherException.Kind.BAD_RESPONSE -> ActionResult.fail("The service gave me an unusable answer.", "서비스에서 올바른 응답을 받지 못했습니다.")
}

/** SUN_TIMES, UV_INDEX, WEATHER_WEEK, AIR_QUALITY. */
class EnvironmentCommand(
    private val places: PlaceResolver,
    private val env: EnvironmentProvider,
) : Command {
    override val types = listOf("SUN_TIMES", "SUNRISE", "SUNSET", "UV_INDEX", "WEATHER_WEEK", "WEEKLY_FORECAST", "AIR_QUALITY", "DUST")

    override suspend fun execute(action: AiAction): ActionResult = try {
        val place = places.resolve(action.param("city"))
        when (action.kind()) {
            "SUN_TIMES", "SUNRISE", "SUNSET" -> {
                val d = env.outlook(place.lat, place.lon, 1).first()
                ActionResult.ok(
                    "In ${place.label}, the sun rises at ${d.sunrise} and sets at ${d.sunset}.",
                    "${place.label}의 일출은 ${d.sunrise}, 일몰은 ${d.sunset}입니다.",
                )
            }
            "UV_INDEX" -> {
                val uv = env.outlook(place.lat, place.lon, 1).first().uvMax
                    ?: return ActionResult.fail("UV data isn't available.", "자외선 정보를 가져올 수 없습니다.")
                val (en, ko) = EnvironmentProvider.uvCategory(uv)
                ActionResult.ok("Today's peak UV index is ${ExpressionEvaluator.format(uv)}, which is $en.", "오늘 최고 자외선 지수는 ${ExpressionEvaluator.format(uv)}로 '$ko' 수준입니다.")
            }
            "AIR_QUALITY", "DUST" -> {
                val air = env.airQuality(place.lat, place.lon)
                val (en, ko) = EnvironmentProvider.aqiCategory(air.usAqi)
                val pm = air.pm25?.let { " Fine dust is ${it.toInt()} micrograms." } ?: ""
                val pmKo = air.pm25?.let { " 초미세먼지 ${it.toInt()}㎍/㎥." } ?: ""
                ActionResult.ok("The air quality index is ${air.usAqi}, which is $en.$pm", "대기질 지수는 ${air.usAqi}로 '$ko'입니다.$pmKo")
            }
            else -> {
                val days = env.outlook(place.lat, place.lon, 7)
                val data = days.joinToString("\n") {
                    "${it.date}: ${WeatherText.conditionEn(it.code)}, high ${it.maxC.toInt()}C, low ${it.minC.toInt()}C" +
                        (it.rainChance?.let { p -> ", rain $p%" } ?: "")
                }
                val ko = days.take(5).joinToString("\n") {
                    val dow = LocalDate.parse(it.date).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)
                    "$dow ${WeatherText.conditionKo(it.code)} ${it.minC.toInt()}~${it.maxC.toInt()}°"
                }
                ActionResult(
                    success = true,
                    speech = "Here is the week ahead for ${place.label}. Please see the subtitles.",
                    subtitle = ko,
                    data = "Seven day outlook for ${place.label}:\n$data",
                    narrate = true,
                )
            }
        }
    } catch (e: WeatherException) {
        weatherFailure(e)
    }
}

class WhereAmICommand(
    private val context: Context,
    private val location: LocationProvider,
) : Command {
    override val types = listOf("WHERE_AM_I", "MY_LOCATION", "CURRENT_LOCATION")

    @Suppress("DEPRECATION")
    override suspend fun execute(action: AiAction): ActionResult {
        if (!Perms.hasLocation(context)) {
            return ActionResult.fail("I need location permission for that. You can allow it in settings.", "위치 권한이 필요합니다. 설정에서 허용해 주세요.")
        }
        val fix = location.currentOrNull() ?: return ActionResult.fail(
            "I couldn't get a location fix right now.", "현재 위치를 확인하지 못했습니다.",
        )
        val address = withContext(Dispatchers.IO) {
            try {
                if (Geocoder.isPresent()) Geocoder(context, Locale.KOREA).getFromLocation(fix.latitude, fix.longitude, 1)?.firstOrNull() else null
            } catch (e: Exception) {
                null
            }
        }
        val line = address?.getAddressLine(0)
        return if (line != null) {
            ActionResult.ok("You are near ${address.locality ?: address.subLocality ?: "your current position"}.", "현재 위치는 $line 부근입니다.")
        } else {
            ActionResult.ok("You are at latitude %.3f, longitude %.3f.".format(fix.latitude, fix.longitude), "위도 %.3f, 경도 %.3f 입니다.".format(fix.latitude, fix.longitude))
        }
    }
}

class ShareLocationCommand(
    private val context: Context,
    private val location: LocationProvider,
    private val launcher: ActivityLauncher,
) : Command {
    override val types = listOf("SHARE_LOCATION", "SEND_LOCATION")

    override suspend fun execute(action: AiAction): ActionResult {
        if (!Perms.hasLocation(context)) {
            return ActionResult.fail("I need location permission for that.", "위치 권한이 필요합니다.")
        }
        val fix = location.currentOrNull() ?: return ActionResult.fail("I couldn't get a location fix.", "현재 위치를 확인하지 못했습니다.")
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "https://maps.google.com/?q=${fix.latitude},${fix.longitude}")
        }
        return launcher.launch(Intent.createChooser(send, "JARVIS"), "Share location")
            .toResult(ActionResult.fail("I couldn't open the share sheet.", "공유 창을 열 수 없습니다."))
    }
}

/** Top headlines from Google News RSS (no API key). The AI phrases them in English + Korean. */
class NewsCommand : Command {
    override val types = listOf("NEWS", "HEADLINES", "GET_NEWS")

    override suspend fun execute(action: AiAction): ActionResult {
        val topic = action.param("topic") ?: action.param("query")
        val base = if (topic == null) "https://news.google.com/rss".toHttpUrl().newBuilder()
        else "https://news.google.com/rss/search".toHttpUrl().newBuilder().addQueryParameter("q", topic)
        val url = base.addQueryParameter("hl", "ko").addQueryParameter("gl", "KR").addQueryParameter("ceid", "KR:ko").build()
        return try {
            val xml = withContext(Dispatchers.IO) {
                Http.client.await(Request.Builder().url(url).header("User-Agent", "JARVIS-Android/1.2").build()).use {
                    if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
                    it.body?.string().orEmpty()
                }
            }
            val titles = parseTitles(xml).take(5)
            if (titles.isEmpty()) return ActionResult.fail("I found no headlines.", "표시할 뉴스가 없습니다.")
            ActionResult(
                success = true,
                speech = "Here are the top headlines. Please see the subtitles.",
                subtitle = titles.joinToString("\n") { "• $it" },
                data = "Top news headlines (Korean)${topic?.let { " about $it" } ?: ""}:\n" + titles.joinToString("\n") { "- $it" },
                narrate = true,
            )
        } catch (e: IOException) {
            ActionResult.fail("Connection unavailable.", "인터넷 연결을 사용할 수 없습니다.")
        }
    }

    companion object {
        /** Item titles from an RSS document, with the trailing " - Publisher" removed. */
        fun parseTitles(xml: String): List<String> =
            Regex("<item>.*?<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).findAll(xml).map {
                it.groupValues[1].removePrefix("<![CDATA[").removeSuffix("]]>")
                    .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
                    .substringBeforeLast(" - ").trim()
            }.filter { it.isNotEmpty() }.toList()
    }
}

class CryptoPriceCommand : Command {
    override val types = listOf("CRYPTO_PRICE", "COIN_PRICE", "BITCOIN_PRICE")

    override suspend fun execute(action: AiAction): ActionResult {
        val coin = coinId(action.param("coin") ?: "bitcoin")
        return try {
            val url = "https://api.coingecko.com/api/v3/simple/price".toHttpUrl().newBuilder()
                .addQueryParameter("ids", coin).addQueryParameter("vs_currencies", "usd,krw")
                .addQueryParameter("include_24hr_change", "true").build()
            val body = withContext(Dispatchers.IO) {
                Http.client.await(Request.Builder().url(url).header("User-Agent", "JARVIS-Android/1.2").build()).use {
                    if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
                    it.body?.string().orEmpty()
                }
            }
            val o = JSONObject(body).optJSONObject(coin) ?: return ActionResult.fail("I don't know that coin.", "해당 코인을 찾을 수 없습니다.")
            val usd = o.getDouble("usd")
            val krw = o.getDouble("krw")
            val change = o.optDouble("usd_24h_change", 0.0)
            val dir = if (change >= 0) "up" else "down"
            ActionResult.ok(
                "$coin is at ${"%,.0f".format(usd)} dollars, $dir ${"%.1f".format(kotlin.math.abs(change))} percent in 24 hours.",
                "${coin} 현재가는 ${"%,.0f".format(krw)}원 ($${"%,.0f".format(usd)}), 24시간 ${"%+.1f".format(change)}% 입니다.",
            )
        } catch (e: Exception) {
            ActionResult.fail("I couldn't fetch the price.", "시세를 가져오지 못했습니다.")
        }
    }

    companion object {
        fun coinId(raw: String): String {
            val s = raw.trim().lowercase()
            return when (s) {
                "btc", "비트코인", "bitcoin" -> "bitcoin"
                "eth", "이더리움", "ethereum" -> "ethereum"
                "xrp", "리플", "ripple" -> "ripple"
                "doge", "도지코인", "dogecoin" -> "dogecoin"
                "sol", "솔라나", "solana" -> "solana"
                "ada", "에이다", "cardano" -> "cardano"
                "usdt", "테더", "tether" -> "tether"
                "bnb", "바이낸스코인" -> "binancecoin"
                else -> s.replace(" ", "-")
            }
        }
    }
}

package com.jarvis.assistant.holo

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import com.jarvis.assistant.calendar.CalendarReader
import com.jarvis.assistant.command.commands.NewsCommand
import com.jarvis.assistant.command.commands.PlaceResolver
import com.jarvis.assistant.data.repository.NoteRepository
import com.jarvis.assistant.data.repository.ReminderRepository
import com.jarvis.assistant.data.repository.TaskRepository
import com.jarvis.assistant.tools.ExpressionEvaluator
import com.jarvis.assistant.util.Http
import com.jarvis.assistant.util.JLog
import com.jarvis.assistant.util.await
import com.jarvis.assistant.weather.EnvironmentProvider
import com.jarvis.assistant.weather.WeatherProvider
import com.jarvis.assistant.weather.WeatherText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.coroutines.cancellation.CancellationException

/** Loads and caches the content of every holographic window. Each window loads on its own, like a boot sequence. */
class HoloDataHub(
    private val context: Context,
    private val scope: CoroutineScope,
    private val weather: WeatherProvider,
    private val places: PlaceResolver,
    private val env: EnvironmentProvider,
    private val tasks: TaskRepository,
    private val notes: NoteRepository,
    private val reminders: ReminderRepository,
    private val calendar: CalendarReader,
) {
    private val _content = MutableStateFlow<Map<HoloPanel, HoloContent>>(emptyMap())
    val content: StateFlow<Map<HoloPanel, HoloContent>> = _content

    private val jobs = mutableMapOf<HoloPanel, Job>()

    fun contentOf(panel: HoloPanel): HoloContent = _content.value[panel] ?: HoloContent()

    /** Load windows that have no data yet (call whenever the set of open windows changes). */
    fun ensureLoaded(panels: Collection<HoloPanel>) {
        panels.forEach { p -> if (_content.value[p] == null) refresh(p) }
    }

    fun refresh(panel: HoloPanel) {
        jobs[panel]?.cancel()
        _content.update { it + (panel to (it[panel]?.copy(status = HoloContent.Status.LOADING) ?: HoloContent())) }
        jobs[panel] = scope.launch {
            val result = try {
                load(panel)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                JLog.w("HoloData", "$panel failed", e)
                HoloContent(HoloContent.Status.ERROR, note = "불러오지 못했습니다")
            }
            _content.update { it + (panel to result) }
        }
    }

    fun refreshAll(panels: Collection<HoloPanel>) = panels.forEach(::refresh)

    private suspend fun load(panel: HoloPanel): HoloContent = when (panel) {
        HoloPanel.WEATHER -> weatherContent()
        HoloPanel.AIR -> airContent()
        HoloPanel.CALENDAR -> calendarContent()
        HoloPanel.TASKS -> tasksContent()
        HoloPanel.NOTES -> notesContent()
        HoloPanel.REMINDERS -> remindersContent()
        HoloPanel.SYSTEM -> systemContent()
        HoloPanel.NEWS -> newsContent()
        HoloPanel.MARKET -> marketContent()
        HoloPanel.MUSIC -> musicContent()
        HoloPanel.CLOCK -> HoloContent(
            HoloContent.Status.READY,
            lines = listOf("Asia/Seoul|서울", "America/New_York|뉴욕", "Europe/London|런던", "Asia/Tokyo|도쿄", "Australia/Sydney|시드니"),
        )
    }

    private suspend fun weatherContent(): HoloContent {
        val place = places.resolve(null)
        val r = weather.forPoint(place.lat, place.lon, place.label)
        fun t(v: Double) = v.roundToInt()
        return HoloContent(
            HoloContent.Status.READY,
            big = "${t(r.currentC)}°",
            sub = "${WeatherText.conditionKo(r.currentCode)} · ${r.place}",
            lines = buildList {
                add("체감 ${t(r.feelsLikeC)}°  습도 ${r.humidity ?: "-"}%")
                add("오늘 ${t(r.today.minC)}° ~ ${t(r.today.maxC)}°  비 ${r.today.precipProbability ?: 0}%")
                r.tomorrow?.let { add("내일 ${WeatherText.conditionKo(it.code)} ${t(it.minC)}°~${t(it.maxC)}°") }
            },
        )
    }

    private suspend fun airContent(): HoloContent {
        val place = places.resolve(null)
        val air = env.airQuality(place.lat, place.lon)
        val day = env.outlook(place.lat, place.lon, 1).first()
        val (_, ko) = EnvironmentProvider.aqiCategory(air.usAqi)
        return HoloContent(
            HoloContent.Status.READY,
            big = "AQI ${air.usAqi}",
            sub = ko,
            lines = buildList {
                air.pm25?.let { add("초미세먼지 ${it.toInt()}㎍/㎥") }
                day.uvMax?.let { add("자외선 ${ExpressionEvaluator.format(it)} (${EnvironmentProvider.uvCategory(it).second})") }
                add("일출 ${day.sunrise ?: "-"}  일몰 ${day.sunset ?: "-"}")
            },
        )
    }

    private suspend fun calendarContent(): HoloContent {
        if (!calendar.hasPermission()) {
            return HoloContent(HoloContent.Status.EMPTY, note = "설정에서 캘린더 권한을 허용하세요")
        }
        val zone = ZoneId.systemDefault()
        val start = LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli()
        val events = calendar.between(start, start + 2 * 86_400_000L)
        if (events.isEmpty()) return HoloContent(HoloContent.Status.EMPTY, big = "0", sub = "오늘·내일 일정 없음")
        val fmt = DateTimeFormatter.ofPattern("M/d HH:mm", Locale.KOREAN)
        return HoloContent(
            HoloContent.Status.READY,
            big = "${events.size}",
            sub = "예정된 일정",
            lines = events.take(8).map { "${Instant.ofEpochMilli(it.first).atZone(zone).format(fmt)}  ${it.second}" },
        )
    }

    private suspend fun tasksContent(): HoloContent {
        val todo = tasks.open("todo")
        val shop = tasks.open("shopping")
        val lines = todo.map { "□ ${it.text}" } + shop.map { "🛒 ${it.text}" }
        if (lines.isEmpty()) return HoloContent(HoloContent.Status.EMPTY, big = "0", sub = "할 일 없음")
        return HoloContent(HoloContent.Status.READY, big = "${lines.size}", sub = "열린 항목", lines = lines.take(10))
    }

    private suspend fun notesContent(): HoloContent {
        val list = notes.latest(8)
        if (list.isEmpty()) return HoloContent(HoloContent.Status.EMPTY, big = "0", sub = "\"메모해줘\" 라고 말해 보세요")
        return HoloContent(HoloContent.Status.READY, big = "${list.size}", sub = "최근 메모", lines = list.map { "• ${it.text}" })
    }

    private suspend fun remindersContent(): HoloContent {
        val list = reminders.pending()
        if (list.isEmpty()) return HoloContent(HoloContent.Status.EMPTY, big = "0", sub = "예정된 알림 없음")
        val fmt = DateTimeFormatter.ofPattern("M/d HH:mm")
        return HoloContent(
            HoloContent.Status.READY,
            big = "${list.size}",
            sub = "예정된 알림",
            lines = list.take(8).map { "⏰ ${Instant.ofEpochMilli(it.triggerAt).atZone(ZoneId.systemDefault()).format(fmt)}  ${it.text}" },
        )
    }

    private fun systemContent(): HoloContent {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100) } ?: -1
        val charging = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1).let {
            it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL
        }
        val temp = (battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        val stat = StatFs(Environment.getDataDirectory().path)
        val mem = ActivityManager.MemoryInfo().also { (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val net = when {
            caps == null -> "오프라인"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "모바일 데이터"
            else -> "연결됨"
        }
        return HoloContent(
            HoloContent.Status.READY,
            big = "$level%",
            sub = if (charging) "충전 중 · ${temp}°C" else "배터리 · ${temp}°C",
            lines = listOf(
                "저장공간 %.1fGB 남음".format(stat.availableBytes / 1_073_741_824.0),
                "메모리 %.1f / %.1fGB".format(mem.availMem / 1_073_741_824.0, mem.totalMem / 1_073_741_824.0),
                "네트워크 $net",
            ),
        )
    }

    private suspend fun newsContent(): HoloContent {
        val url = "https://news.google.com/rss".toHttpUrl().newBuilder()
            .addQueryParameter("hl", "ko").addQueryParameter("gl", "KR").addQueryParameter("ceid", "KR:ko").build()
        val xml = fetch(url.toString())
        val titles = NewsCommand.parseTitles(xml).take(7)
        if (titles.isEmpty()) return HoloContent(HoloContent.Status.EMPTY, note = "뉴스가 없습니다")
        return HoloContent(HoloContent.Status.READY, big = "TOP", sub = "헤드라인", lines = titles.map { "• $it" })
    }

    private suspend fun marketContent(): HoloContent {
        val url = "https://api.coingecko.com/api/v3/simple/price".toHttpUrl().newBuilder()
            .addQueryParameter("ids", "bitcoin,ethereum,ripple,solana")
            .addQueryParameter("vs_currencies", "krw")
            .addQueryParameter("include_24hr_change", "true").build()
        val json = JSONObject(fetch(url.toString()))
        val names = listOf("bitcoin" to "BTC", "ethereum" to "ETH", "ripple" to "XRP", "solana" to "SOL")
        val lines = names.mapNotNull { (id, label) ->
            json.optJSONObject(id)?.let {
                val change = it.optDouble("krw_24h_change", 0.0)
                "%s  %,.0f원  %s%.1f%%".format(label, it.getDouble("krw"), if (change >= 0) "▲" else "▼", abs(change))
            }
        }
        if (lines.isEmpty()) return HoloContent(HoloContent.Status.ERROR, note = "시세를 가져오지 못했습니다")
        return HoloContent(HoloContent.Status.READY, big = "KRW", sub = "24시간 변동", lines = lines)
    }

    private fun musicContent(): HoloContent {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val playing = audio.isMusicActive
        return HoloContent(
            HoloContent.Status.READY,
            big = if (playing) "PLAY" else "IDLE",
            sub = if (playing) "재생 중" else "정지됨",
            lines = listOf("아래 버튼으로 재생을 제어하세요", "\"아이유 노래 틀어줘\"처럼 말해도 됩니다"),
            actions = listOf(HoloAction("prev", "◀◀"), HoloAction("toggle", if (playing) "❚❚" else "▶"), HoloAction("next", "▶▶")),
        )
    }

    private suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        Http.client.await(Request.Builder().url(url).header("User-Agent", "JARVIS-Android/1.3").build()).use {
            if (!it.isSuccessful) throw java.io.IOException("HTTP ${it.code}")
            it.body?.string().orEmpty()
        }
    }
}

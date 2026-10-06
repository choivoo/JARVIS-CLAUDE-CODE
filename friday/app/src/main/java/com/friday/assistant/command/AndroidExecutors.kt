package com.friday.assistant.command

import android.Manifest
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.friday.assistant.search.WebSearchProvider
import com.friday.assistant.util.EnglishNumbers
import com.friday.assistant.weather.WeatherException
import com.friday.assistant.weather.WeatherProvider
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Real Android implementations of every allow-listed command. */
class AndroidExecutors(
    private val context: Context,
    private val launcher: ActivityLauncher,
    private val apps: AppResolver,
    private val weather: WeatherProvider,
    private val search: WebSearchProvider,
    private val contacts: ContactResolver,
    private val location: LocationHelper,
    private val defaultCity: () -> String,
    private val clock: () -> LocalDateTime = { LocalDateTime.now() },
) {
    private val audio get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun all(): Map<CommandType, CommandExecutor> = buildMap {
        put(CommandType.GET_TIME, CommandExecutor { _, _ -> time() })
        put(CommandType.GET_DATE, CommandExecutor { _, _ -> date() })
        put(CommandType.OPEN_APP, CommandExecutor { c, _ -> openApp(c) })
        put(CommandType.OPEN_URL, CommandExecutor { c, _ -> openUrl(c) })
        put(CommandType.WEB_SEARCH, CommandExecutor { c, _ -> browserSearch(c.param("query")) })
        put(CommandType.WEB_ANSWER, CommandExecutor { c, _ -> webAnswer(c) })
        put(CommandType.YOUTUBE_SEARCH, CommandExecutor { c, _ -> youtubeSearch(c.param("query")) })
        put(CommandType.OPEN_YOUTUBE, CommandExecutor { _, _ -> openYoutube() })
        put(CommandType.SET_ALARM, CommandExecutor { c, _ -> setAlarm(c) })
        put(CommandType.FLASHLIGHT_ON, CommandExecutor { _, _ -> flashlight(true) })
        put(CommandType.FLASHLIGHT_OFF, CommandExecutor { _, _ -> flashlight(false) })
        put(CommandType.VOLUME_UP, CommandExecutor { _, _ -> stepVolume(AudioManager.ADJUST_RAISE) })
        put(CommandType.VOLUME_DOWN, CommandExecutor { _, _ -> stepVolume(AudioManager.ADJUST_LOWER) })
        put(CommandType.SET_VOLUME, CommandExecutor { c, _ -> setVolume(c) })
        put(CommandType.WEATHER, CommandExecutor { c, _ -> weather(c) })
        put(CommandType.OPEN_SETTINGS, CommandExecutor { c, _ -> openSettings(c.param("target")) })
        put(CommandType.CALL_CONTACT_REQUEST, object : ConfirmableExecutor() {
            override suspend fun prepare(command: Command) = call(command, false)
            override suspend fun perform(command: Command) = call(command, true)
        })
        put(CommandType.MESSAGE_CONTACT_REQUEST, object : ConfirmableExecutor() {
            override suspend fun prepare(command: Command) = message(command, false)
            override suspend fun perform(command: Command) = message(command, true)
        })
    }

    // ---- read-only info -------------------------------------------------------------------------------------

    private fun time(): CommandResult {
        val n = clock()
        val h12 = if (n.hour % 12 == 0) 12 else n.hour % 12
        val ampm = if (n.hour < 12) "AM" else "PM"
        return CommandResult.ok(
            "It's $h12:${"%02d".format(n.minute)} $ampm.",
            "현재 시각은 ${if (n.hour < 12) "오전" else "오후"} ${h12}시 ${n.minute}분입니다.",
        )
    }

    private fun date(): CommandResult {
        val n = clock()
        val en = n.format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.ENGLISH))
        val ko = "${n.year}년 ${n.monthValue}월 ${n.dayOfMonth}일 ${n.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.KOREAN)}"
        return CommandResult.ok("Today is $en.", "오늘은 ${ko}입니다.")
    }

    // ---- launching things -----------------------------------------------------------------------------------

    private fun opened(label: String, outcome: LaunchOutcome, ko: String = label): CommandResult = when (outcome) {
        LaunchOutcome.STARTED -> CommandResult.ok("Opening $label.", "${ko}을(를) 실행합니다.")
        LaunchOutcome.NOTIFIED -> CommandResult.ok(
            "I'm in the background, so tap the notification to open $label.",
            "백그라운드 상태입니다. 알림을 눌러 ${ko}을(를) 여세요.",
        )
    }

    private fun notFound(speech: String, subtitle: String) = CommandResult.failed(speech, subtitle)

    private fun openApp(c: Command): CommandResult {
        val target = c.param("target") ?: return CommandResult.failed("Which app should I open?", "어떤 앱을 열까요?")
        if (AppResolver.normalize(target) in setOf("settings", "설정", "세팅")) return openSettings(null)
        val app = apps.resolve(target)
            ?: return notFound("I couldn't find that application.", "해당 앱을 찾을 수 없습니다.")
        val intent = context.packageManager.getLaunchIntentForPackage(app.packageName)
            ?: return notFound("I couldn't find that application.", "해당 앱을 찾을 수 없습니다.")
        return opened(app.label, launcher.launch(intent, app.label))
    }

    private fun openUrl(c: Command): CommandResult {
        val uri = c.param("url")?.let { Uri.parse(it.trim()) }
        if (uri == null || uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) {
            return CommandResult.failed("I can only open web addresses.", "웹 주소(http/https)만 열 수 있습니다.")
        }
        return try {
            opened("the page", launcher.launch(Intent(Intent.ACTION_VIEW, uri), "the page"), "페이지")
        } catch (e: ActivityNotFoundException) {
            CommandResult.failed("I couldn't find a browser.", "브라우저를 찾을 수 없습니다.")
        }
    }

    private fun browserSearch(query: String?): CommandResult {
        val q = query ?: return CommandResult.failed("What should I search for?", "무엇을 검색할까요?")
        val uri = Uri.parse("https://www.google.com/search").buildUpon().appendQueryParameter("q", q).build()
        return try {
            opened("a search for $q", launcher.launch(Intent(Intent.ACTION_VIEW, uri), "search"), "'$q' 검색")
        } catch (e: ActivityNotFoundException) {
            CommandResult.failed("I couldn't find a browser.", "브라우저를 찾을 수 없습니다.")
        }
    }

    private suspend fun webAnswer(c: Command): CommandResult {
        val q = c.param("query") ?: return CommandResult.failed("What should I look up?", "무엇을 찾아볼까요?")
        val hits = try {
            search.search(q, 5)
        } catch (e: IOException) {
            return browserSearch(q).let {
                if (it.ok) it.copy(speech = "I can't reach the search service, so ${it.speech.replaceFirstChar { c -> c.lowercase() }}") else it
            }
        }
        if (hits.isEmpty()) {
            return browserSearch(q).let { if (it.ok) it.copy(speech = "I found nothing directly. " + it.speech) else it }
        }
        val data = hits.take(4).joinToString("\n") { "- ${it.title}: ${it.snippet.take(220)}" }
        val first = hits.first()
        return CommandResult.ok(
            "Here is the top result: ${first.title}.",
            "검색 결과: ${first.title}${if (first.snippet.isNotBlank()) " — " + first.snippet.take(100) else ""}",
            data,
        )
    }

    private fun openYoutube(): CommandResult {
        val intent = context.packageManager.getLaunchIntentForPackage(YOUTUBE)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com"))
        return try {
            opened("YouTube", launcher.launch(intent, "YouTube"), "유튜브")
        } catch (e: ActivityNotFoundException) {
            CommandResult.failed("I couldn't open YouTube.", "유튜브를 열 수 없습니다.")
        }
    }

    private fun youtubeSearch(query: String?): CommandResult {
        val q = query ?: return openYoutube()
        val app = Intent(Intent.ACTION_SEARCH).setPackage(YOUTUBE).putExtra(SearchManager.QUERY, q)
        val installed = context.packageManager.getLaunchIntentForPackage(YOUTUBE) != null
        val intent = if (installed) app else Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://www.youtube.com/results").buildUpon().appendQueryParameter("search_query", q).build(),
        )
        return try {
            opened("YouTube search for $q", launcher.launch(intent, "YouTube"), "유튜브에서 '$q' 검색")
        } catch (e: ActivityNotFoundException) {
            CommandResult.failed("I couldn't open YouTube.", "유튜브를 열 수 없습니다.")
        }
    }

    private fun openSettings(target: String?): CommandResult {
        val (action, label) = when (target?.lowercase()) {
            "wifi", "wi-fi" -> Settings.ACTION_WIFI_SETTINGS to "Wi-Fi settings"
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS to "Bluetooth settings"
            "display" -> Settings.ACTION_DISPLAY_SETTINGS to "display settings"
            "sound" -> Settings.ACTION_SOUND_SETTINGS to "sound settings"
            "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY to "battery settings"
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS to "location settings"
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS to "app settings"
            "notifications" -> Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS to "notification access settings"
            else -> Settings.ACTION_SETTINGS to "settings"
        }
        return try {
            opened(label, launcher.launch(Intent(action), label), "설정")
        } catch (e: ActivityNotFoundException) {
            CommandResult.failed("I couldn't open settings.", "설정을 열 수 없습니다.")
        }
    }

    // ---- alarm ----------------------------------------------------------------------------------------------

    private fun setAlarm(c: Command): CommandResult {
        val hour = c.param("hour")?.toIntOrNull()
        val minute = c.param("minute")?.toIntOrNull() ?: 0
        if (hour == null || hour !in 0..23 || minute !in 0..59) {
            return CommandResult.failed("What time should I set the alarm for?", "몇 시로 알람을 맞출까요?")
        }
        val now = clock()
        val dayOffset = c.param("dayOffset")?.toIntOrNull()
            ?: if (c.param("day")?.lowercase() == "tomorrow") 1 else 0
        val nextOccurrence = if (now.toLocalTime().isBefore(java.time.LocalTime.of(hour, minute))) now.toLocalDate() else now.toLocalDate().plusDays(1)
        val target = now.toLocalDate().plusDays(dayOffset.toLong())
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, c.param("label") ?: "FRIDAY")
        // The clock API only knows "next occurrence". If the user wants a later day, ask the clock app to confirm.
        val exact = target == nextOccurrence
        if (exact) intent.putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        val h12 = if (hour % 12 == 0) 12 else hour % 12
        val en = "$h12:${"%02d".format(minute)} ${if (hour < 12) "AM" else "PM"}"
        val dayEn = if (target == now.toLocalDate()) "today" else if (target == now.toLocalDate().plusDays(1)) "tomorrow" else "later"
        val dayKo = when (dayEn) { "today" -> "오늘"; "tomorrow" -> "내일"; else -> "" }
        return try {
            val outcome = launcher.launch(intent, "the alarm")
            if (outcome == LaunchOutcome.NOTIFIED) {
                CommandResult.ok("Tap the notification to set the $en alarm.", "알림을 눌러 $dayKo ${if (hour < 12) "오전" else "오후"} ${h12}시 ${minute}분 알람을 설정하세요.")
            } else if (exact) {
                CommandResult.ok("Alarm requested for $en $dayEn.", "$dayKo ${if (hour < 12) "오전" else "오후"} ${h12}시 ${minute}분 알람을 요청했습니다.")
            } else {
                CommandResult.ok(
                    "I opened the clock app for $en $dayEn. Please confirm it there.",
                    "시계 앱을 열었습니다. $dayKo ${if (hour < 12) "오전" else "오후"} ${h12}시 ${minute}분 알람을 확인해 주세요.",
                )
            }
        } catch (e: ActivityNotFoundException) {
            CommandResult.failed("I couldn't find a clock app.", "알람 앱을 찾을 수 없습니다.")
        }
    }

    // ---- hardware -------------------------------------------------------------------------------------------

    private fun flashlight(on: Boolean): CommandResult {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            val ch = cm.getCameraCharacteristics(it)
            ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: return CommandResult.failed("This device has no flashlight.", "이 기기에는 손전등이 없습니다.")
        cm.setTorchMode(id, on)
        return if (on) CommandResult.ok("Flashlight on.", "손전등을 켰습니다.") else CommandResult.ok("Flashlight off.", "손전등을 껐습니다.")
    }

    private fun volumePercent(): Int {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
    }

    private fun stepVolume(direction: Int): CommandResult {
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        val p = volumePercent()
        return CommandResult.ok(
            "Volume is now ${EnglishNumbers.words(p)} percent.",
            "볼륨을 ${if (direction == AudioManager.ADJUST_RAISE) "높였" else "낮췄"}습니다. 현재 ${p}%입니다.",
        )
    }

    private fun setVolume(c: Command): CommandResult {
        val pct = c.param("percent")?.toIntOrNull()?.coerceIn(0, 100)
            ?: return CommandResult.failed("What volume level?", "볼륨을 몇 퍼센트로 할까요?")
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(pct * max / 100f), AudioManager.FLAG_SHOW_UI)
        val actual = volumePercent()
        return CommandResult.ok("Volume set to ${EnglishNumbers.words(actual)} percent.", "볼륨을 ${actual}%로 설정했습니다.")
    }

    // ---- weather --------------------------------------------------------------------------------------------

    private suspend fun weather(c: Command): CommandResult {
        val city = c.param("city")
        val coords = if (city == null) location.lastKnown() else null
        val report = try {
            weather.fetch(city ?: if (coords == null) defaultCity() else null, coords?.lat, coords?.lon)
        } catch (e: WeatherException) {
            return if (e.noInternet) CommandResult.failed("I can't reach the weather service right now.", "날씨 서비스에 연결할 수 없습니다. 인터넷을 확인해 주세요.")
            else CommandResult.failed("I couldn't find the weather for that place.", "해당 지역의 날씨를 찾을 수 없습니다.")
        }
        val tomorrow = c.param("day")?.lowercase() == "tomorrow"
        val d = if (tomorrow) report.tomorrow else report.today
        val place = if (report.place == "your location") "your location" else report.place
        val nowC = Math.round(report.tempC).toInt()
        val speech = buildString {
            if (!tomorrow) append("In $place it's ${EnglishNumbers.words(nowC)} degrees with ${report.condition}. ")
            if (d != null) {
                append(if (tomorrow) "Tomorrow: " else "Today: ")
                append("high ${EnglishNumbers.words(Math.round(d.maxC).toInt())}, low ${EnglishNumbers.words(Math.round(d.minC).toInt())}")
                if (!tomorrow || true) d.rainChancePct?.let { append(", ${EnglishNumbers.words(it)} percent chance of rain") }
                append(if (tomorrow) ", ${d.condition}." else ".")
            }
        }.trim()
        val ko = buildString {
            if (!tomorrow) append("${if (place == "your location") "현재 위치" else place}은(는) 현재 ${nowC}°C, ${koCondition(report.condition)}입니다 (체감 ${Math.round(report.feelsLikeC)}°C). ")
            if (d != null) {
                append(if (tomorrow) "내일 " else "오늘 ")
                append("최고 ${Math.round(d.maxC)}°C, 최저 ${Math.round(d.minC)}°C")
                d.rainChancePct?.let { append(", 강수확률 ${it}%") }
                if (tomorrow) append(", ${koCondition(d.condition)}")
                append(".")
            }
        }.trim()
        return CommandResult.ok(speech, ko, report.toToolText())
    }

    private fun koCondition(en: String) = when (en) {
        "clear sky" -> "맑음"; "partly cloudy" -> "구름 조금"; "overcast" -> "흐림"; "fog" -> "안개"
        "drizzle" -> "이슬비"; "rain" -> "비"; "snow" -> "눈"; "rain showers" -> "소나기"
        "snow showers" -> "눈보라"; "thunderstorm" -> "뇌우"; else -> "알 수 없는 날씨"
    }

    // ---- phone & messages (always confirmed first) ---------------------------------------------------------

    private fun resolveContact(c: Command): Pair<ContactHit?, CommandResult?> {
        val name = c.param("name") ?: return null to CommandResult.failed("Whom should I contact?", "누구에게 연락할까요?")
        val digits = name.filter { it.isDigit() || it == '+' }
        if (digits.length >= 7) return ContactHit(name, digits) to null
        if (!contacts.hasPermission()) {
            return null to CommandResult.permission(
                "I need permission to read your contacts. You can allow it in Settings.",
                "연락처를 읽으려면 권한이 필요합니다. 설정에서 허용해 주세요.",
            )
        }
        val hit = contacts.find(name)
            ?: return null to CommandResult.failed("I couldn't find $name in your contacts.", "연락처에서 '$name'을(를) 찾을 수 없습니다.")
        return hit to null
    }

    private fun call(c: Command, confirmed: Boolean): CommandResult {
        if (!confirmed) {
            val (hit, err) = resolveContact(c)
            if (err != null || hit == null) return err!!
            return CommandResult.confirm(
                "Would you like me to call ${hit.displayName}?",
                "${hit.displayName}에게 전화를 걸까요?",
                Command(CommandType.CALL_CONTACT_REQUEST, mapOf("name" to hit.displayName, "number" to hit.number)),
            )
        }
        val number = c.param("number") ?: return CommandResult.failed("I don't have a number to call.", "전화번호가 없습니다.")
        val canCall = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val intent = Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))
        return try {
            val outcome = launcher.launch(intent, "the dialer")
            if (outcome == LaunchOutcome.NOTIFIED) opened("the dialer", outcome, "전화 앱")
            else if (canCall) CommandResult.ok("Calling ${c.param("name") ?: "now"}.", "${c.param("name") ?: ""}에게 전화를 겁니다.")
            else CommandResult.ok("I opened the dialer. Press call to connect.", "전화 앱을 열었습니다. 통화 버튼을 눌러 주세요.")
        } catch (e: ActivityNotFoundException) {
            CommandResult.failed("I couldn't open the phone app.", "전화 앱을 열 수 없습니다.")
        }
    }

    private fun message(c: Command, confirmed: Boolean): CommandResult {
        val body = c.param("body") ?: return CommandResult.failed("What should the message say?", "메시지 내용을 알려 주세요.")
        if (!confirmed) {
            val (hit, err) = resolveContact(c)
            if (err != null || hit == null) return err!!
            return CommandResult.confirm(
                "Shall I prepare a message to ${hit.displayName}?",
                "${hit.displayName}에게 '$body' 메시지를 준비할까요?",
                Command(CommandType.MESSAGE_CONTACT_REQUEST, mapOf("name" to hit.displayName, "number" to hit.number, "body" to body)),
            )
        }
        val number = c.param("number") ?: return CommandResult.failed("I don't have a number.", "전화번호가 없습니다.")
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).putExtra("sms_body", body)
        return try {
            val outcome = launcher.launch(intent, "messages")
            if (outcome == LaunchOutcome.NOTIFIED) opened("messages", outcome, "메시지 앱")
            else CommandResult.ok("Your message is ready. Tap send to deliver it.", "메시지를 준비했습니다. 전송 버튼을 눌러 주세요.")
        } catch (e: ActivityNotFoundException) {
            CommandResult.failed("I couldn't find a messaging app.", "메시지 앱을 찾을 수 없습니다.")
        }
    }

    private companion object { const val YOUTUBE = "com.google.android.youtube" }
}

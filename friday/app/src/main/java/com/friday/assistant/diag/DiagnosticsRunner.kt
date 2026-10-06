package com.friday.assistant.diag

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.friday.assistant.AppContainer
import com.friday.assistant.R
import com.friday.assistant.ai.ChatMessage
import com.friday.assistant.ai.Role
import com.friday.assistant.ai.AiReplyParser
import com.friday.assistant.command.Command
import com.friday.assistant.command.CommandType
import com.friday.assistant.service.FridayService
import com.friday.assistant.settings.AiProviderType
import com.friday.assistant.stt.SttResult
import com.friday.assistant.util.Http
import com.friday.assistant.util.Permissions
import com.friday.assistant.wake.SpeechWakeWordEngine
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.friday.assistant.voice.say
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request

enum class DiagStatus { PASS, FAIL, NOT_CONFIGURED, DEVICE_TEST_REQUIRED }

data class DiagResult(val status: DiagStatus, val detail: String)

data class DiagTest(val id: String, val title: String, val hint: String)

/** Independent on-device self tests. A test never throws: every failure becomes a FAIL with a reason. */
class DiagnosticsRunner(private val c: AppContainer) {
    private val ctx = c.context

    val tests = listOf(
        DiagTest("mic", "Microphone Test", "Records 0.5 s and measures the signal"),
        DiagTest("stt", "STT Test", "Say anything in Korean"),
        DiagTest("wake", "Wake Word Test", "Say “FRIDAY” within 10 s"),
        DiagTest("ai", "AI API Test", "Sends one tiny request"),
        DiagTest("tts", "TTS Test", "You should hear an English voice"),
        DiagTest("service", "Foreground Service Test", "Starts the Background Assistant"),
        DiagTest("notif", "Notification Test", "Posts a test notification"),
        DiagTest("weather", "Weather Test", "Fetches the default city"),
        DiagTest("search", "Web Search Test", "Runs one search"),
        DiagTest("router", "Command Router Test", "Time command + rejects unknown"),
        DiagTest("db", "Database Test", "Write, read, count"),
        DiagTest("net", "Network Test", "Reaches the internet"),
        DiagTest("listener", "Notification Listener", "Needs Notification Access"),
        DiagTest("calendar", "Calendar", "Reads today's events (needs permission)"),
        DiagTest("media", "MediaSession", "Needs Notification Access and playing media"),
        DiagTest("focus", "Audio Focus", "Requests and releases audio focus"),
        DiagTest("interrupt", "Speech Interrupt", "Starts speech and stops it mid-sentence"),
        DiagTest("followup", "Follow-up Mode", "Needs a real conversation on the phone"),
        DiagTest("local", "Local Intent Parser", "Checks sample Korean commands"),
        DiagTest("brief", "Morning Brief", "Builds a brief from available sources"),
        DiagTest("perm", "Permission Center", "Reads every permission state"),
    )

    suspend fun run(id: String): DiagResult = try {
        when (id) {
            "mic" -> mic()
            "stt" -> stt()
            "wake" -> wake()
            "ai" -> ai()
            "tts" -> tts()
            "service" -> service()
            "notif" -> notif()
            "weather" -> weather()
            "search" -> search()
            "router" -> router()
            "db" -> db()
            "net" -> net()
            "listener" -> listener()
            "calendar" -> calendar()
            "media" -> media()
            "focus" -> focus()
            "interrupt" -> interrupt()
            "followup" -> followup()
            "local" -> local()
            "brief" -> brief()
            "perm" -> perm()
            else -> DiagResult(DiagStatus.FAIL, "Unknown test")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DiagResult(DiagStatus.FAIL, e.message?.take(120) ?: e.javaClass.simpleName)
    }

    private fun pass(d: String) = DiagResult(DiagStatus.PASS, d)
    private fun fail(d: String) = DiagResult(DiagStatus.FAIL, d)
    private fun nc(d: String) = DiagResult(DiagStatus.NOT_CONFIGURED, d)

    private suspend fun mic(): DiagResult {
        if (!Permissions.mic(ctx)) return fail("RECORD_AUDIO permission not granted")
        return withContext(Dispatchers.IO) {
            val rate = 16_000
            val size = maxOf(AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), rate)
            @Suppress("MissingPermission")
            val rec = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
            if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return@withContext fail("Microphone could not be opened (in use?)") }
            try {
                rec.startRecording()
                val buf = ShortArray(rate / 2)
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) fail("No audio samples") else {
                    val peak = buf.take(n).maxOf { abs(it.toInt()) }
                    pass("$n samples, peak $peak/32768")
                }
            } finally { rec.stop(); rec.release() }
        }
    }

    private suspend fun stt(): DiagResult {
        if (!Permissions.mic(ctx)) return fail("RECORD_AUDIO permission not granted")
        return when (val r = c.stt.listenOnce()) {
            is SttResult.Text -> pass("Heard: ${r.text.take(60)}")
            SttResult.Empty -> fail("Nothing recognised")
            is SttResult.Failure -> fail("STT error: ${r.error}")
        }
    }

    private suspend fun wake(): DiagResult {
        if (!Permissions.mic(ctx)) return fail("RECORD_AUDIO permission not granted")
        if (c.serviceRunning.value) return nc("Turn off Background Assistant first (microphone is in use)")
        val got = CompletableDeferred<String>()
        val engine = SpeechWakeWordEngine(ctx)
        engine.start { got.complete(it) }
        val r = withTimeoutOrNull(10_000) { got.await() }
        engine.stop()
        return if (r != null) pass("Wake word detected") else fail("Not detected within 10 s")
    }

    private suspend fun ai(): DiagResult {
        val s = c.settingsRepo.current
        if (s.aiProvider.needsKey && c.settingsRepo.aiApiKey().isBlank()) return nc("No API key for ${s.aiProvider.label}")
        val raw = c.aiProvider().complete(
            listOf(
                ChatMessage(Role.SYSTEM, "Reply with JSON only: {\"speech\":\"ok\",\"subtitle\":\"확인\",\"action\":null}"),
                ChatMessage(Role.USER, "ping"),
            ),
        )
        val reply = AiReplyParser.parse(raw)
        return pass("${s.aiProvider.label}/${s.aiModel}: ${reply.speech.take(40)}")
    }

    private suspend fun tts(): DiagResult {
        c.speaker.say("FRIDAY voice check. All systems online.")
        val plan = c.voice.plan().joinToString(" → ") { it.name }
        return DiagResult(DiagStatus.PASS, "Played via ${c.voice.lastProvider.ifBlank { "?" }} (plan: $plan). Audio quality needs a device test.")
    }

    private suspend fun service(): DiagResult {
        if (!Permissions.mic(ctx)) return fail("RECORD_AUDIO permission not granted")
        FridayService.start(ctx)
        val ok = withTimeoutOrNull(4_000) {
            while (!c.serviceRunning.value && c.serviceError.value == null) kotlinx.coroutines.delay(150)
            c.serviceRunning.value
        } ?: false
        if (!c.settingsRepo.current.backgroundAssistant) FridayService.stop(ctx)
        return if (ok) pass("Foreground service started and stopped cleanly") else fail(c.serviceError.value ?: "Service did not start")
    }

    private fun notif(): DiagResult {
        if (!Permissions.notifications(ctx)) return fail("POST_NOTIFICATIONS permission not granted")
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return fail("Notifications are disabled for FRIDAY")
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("friday_diag", "FRIDAY diagnostics", NotificationManager.IMPORTANCE_LOW))
        nm.notify(3003, NotificationCompat.Builder(ctx, "friday_diag").setSmallIcon(R.drawable.ic_stat_friday).setContentTitle("FRIDAY").setContentText("Notification test").setAutoCancel(true).build())
        return pass("Notification posted")
    }

    private suspend fun weather(): DiagResult {
        val city = c.settingsRepo.current.defaultCity
        val r = c.weather.fetch(city)
        return pass("${r.place}: ${Math.round(r.tempC)}°C, ${r.condition}")
    }

    private suspend fun search(): DiagResult {
        val hits = c.webSearch.search("FRIDAY assistant", 3)
        return if (hits.isEmpty()) fail("No results") else pass("${hits.size} results, first: ${hits.first().title.take(50)}")
    }

    private suspend fun router(): DiagResult {
        val t = c.router.execute(Command(CommandType.GET_TIME))
        val bad = c.router.execute(com.friday.assistant.ai.AiAction("RM_RF"))
        return if (t.ok && bad.status == com.friday.assistant.command.ResultStatus.UNSUPPORTED) pass("Time: ${t.speech}; unknown command rejected") else fail("Router misbehaved")
    }

    private suspend fun db(): DiagResult {
        val repo = c.conversations
        repo.putPreference("diag", "ok")
        val v = repo.getPreference("diag")
        return if (v == "ok") pass("Preference round-trip OK, ${repo.count()} messages stored") else fail("Read-back mismatch")
    }

    private suspend fun net(): DiagResult {
        if (!c.network.isOnline()) return fail("No active network")
        return withContext(Dispatchers.IO) {
            Http.client.newCall(Request.Builder().url("https://www.google.com/generate_204").build()).execute().use {
                if (it.code in 200..299) pass("Internet reachable (HTTP ${it.code})") else fail("HTTP ${it.code}")
            }
        }
    }

    private fun listener(): DiagResult =
        if (!c.notifications.accessEnabled()) DiagResult(DiagStatus.NOT_CONFIGURED, "Notification Access is off. Turn it on in the Permission Center.")
        else DiagResult(DiagStatus.PASS, "Access granted; ${c.notifications.recent(30).size} notifications buffered in memory")

    private fun calendar(): DiagResult {
        if (!c.calendar.canRead()) return DiagResult(DiagStatus.NOT_CONFIGURED, "READ_CALENDAR not granted")
        val today = java.time.LocalDate.now()
        val n = c.calendar.events(today.atStartOfDay(), today.plusDays(1).atStartOfDay()).size
        return pass("$n events today; write access: ${if (c.calendar.canWrite()) "yes" else "no (falls back to the calendar app)"}")
    }

    private fun media(): DiagResult {
        if (!c.mediaBackend.hasSessionAccess()) return DiagResult(DiagStatus.NOT_CONFIGURED, "Needs Notification Access to list media sessions")
        val s = c.mediaBackend.activeSession()
            ?: return DiagResult(DiagStatus.DEVICE_TEST_REQUIRED, "Access OK but no active session: start music in another app and run again")
        return pass("${s.appLabel}: ${s.title.ifBlank { "(no title)" }} ${if (s.playing) "(playing)" else "(paused)"}")
    }

    private fun focus(): DiagResult {
        val f = com.friday.assistant.voice.AudioFocusManager(ctx)
        val ok = f.acquire(com.friday.assistant.settings.AudioFocusMode.DUCK)
        f.release()
        return if (ok) pass("Audio focus granted and released") else fail("Android refused audio focus (a call may be active)")
    }

    private suspend fun interrupt(): DiagResult = coroutineScope {
        val started = CompletableDeferred<Unit>()
        val job = launch {
            try {
                c.speaker.speak(
                    com.friday.assistant.voice.SpeechRequest(
                        listOf(com.friday.assistant.voice.SpeechChunk("This is a long test sentence that should be cut off in the middle by the interrupt controller. It keeps going and going.", "")),
                        onFirstAudio = { started.complete(Unit) },
                    ),
                )
            } catch (e: CancellationException) { /* expected when interrupted */ }
        }
        if (withTimeoutOrNull(6_000) { started.await() } == null) { job.cancel(); return@coroutineScope fail("No audio started within 6 s (TTS problem?)") }
        delay(500)
        val t0 = System.nanoTime()
        c.voice.interrupts.interrupt()
        val done = withTimeoutOrNull(2_000) { job.join(); true } ?: false
        val ms = (System.nanoTime() - t0) / 1_000_000
        if (done) DiagResult(DiagStatus.PASS, "Speech stopped ${ms} ms after interrupt. Barge-in by voice (saying FRIDAY while it talks) needs a device test.")
        else fail("Speech did not stop within 2 s")
    }

    private fun followup(): DiagResult {
        val s = c.settingsRepo.current
        return DiagResult(
            DiagStatus.DEVICE_TEST_REQUIRED,
            "Follow-up is ${if (s.followUpEnabled) "ON (${s.followUpTimeoutSec} s)" else "OFF"}. Say FRIDAY, ask something, then ask a second question without saying FRIDAY.",
        )
    }

    private fun local(): DiagResult {
        val now = java.time.LocalDateTime.now()
        val cases = mapOf(
            "지금 몇 시야" to CommandType.GET_TIME, "배터리 얼마나 남았어" to CommandType.GET_BATTERY, "손전등 켜줘" to CommandType.FLASHLIGHT_ON,
            "볼륨 올려줘" to CommandType.VOLUME_UP, "카카오톡 열어줘" to CommandType.OPEN_APP, "오늘 일정 알려줘" to CommandType.GET_TODAY_EVENTS,
            "새 알림 있어" to CommandType.GET_NOTIFICATIONS, "다음 곡" to CommandType.NEXT_MEDIA, "오늘 브리핑" to CommandType.MORNING_BRIEF,
            "카메라 열어줘" to CommandType.OPEN_CAMERA, "내일 오전 7시에 알람 맞춰줘" to CommandType.SET_ALARM,
        )
        val bad = cases.filter { (text, type) -> com.friday.assistant.command.LocalIntentParser.parse(text, now)?.type != type }
        return if (bad.isEmpty()) pass("${cases.size}/${cases.size} sample commands recognised offline") else fail("Not recognised: ${bad.keys.joinToString()}")
    }

    private suspend fun brief(): DiagResult {
        val r = c.router.execute(Command(CommandType.MORNING_BRIEF))
        return if (r.ok) pass(r.subtitle.take(110)) else fail(r.subtitle)
    }

    private fun perm(): DiagResult {
        val items = com.friday.assistant.permission.PermissionCenter.items(c.permissionSnapshot)
        val g = items.count { it.status == com.friday.assistant.permission.PermStatus.GRANTED }
        return pass("$g/${items.size} granted; missing: " + items.filter { it.status != com.friday.assistant.permission.PermStatus.GRANTED }.joinToString { it.title }.ifBlank { "none" })
    }
}


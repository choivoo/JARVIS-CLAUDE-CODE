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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request

enum class DiagStatus { PASS, FAIL, NOT_CONFIGURED }

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
        c.speaker.speak("FRIDAY voice check. All systems online.")
        return pass("Played with ${c.settingsRepo.current.ttsProvider.label} (falls back to Android TTS)")
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
}

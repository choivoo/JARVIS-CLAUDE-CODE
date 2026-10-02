package com.jarvis.assistant.command.commands

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import com.jarvis.assistant.ai.AiAction
import com.jarvis.assistant.command.ActionResult
import com.jarvis.assistant.command.ActivityLauncher
import com.jarvis.assistant.command.AppResolver
import com.jarvis.assistant.command.Command
import com.jarvis.assistant.util.Perms
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------------------------
// Web / stores / music search
// ---------------------------------------------------------------------------------------------

/** SEARCH_SITE {site, query} plus shortcuts such as SEARCH_NAVER, SEARCH_IMAGES, TRANSLATE_TEXT. */
class SearchSiteCommand(private val launcher: ActivityLauncher) : Command {
    override val types = listOf(
        "SEARCH_SITE", "SEARCH_NAVER", "SEARCH_WIKIPEDIA", "SEARCH_IMAGES", "SEARCH_NAMU", "SEARCH_GITHUB",
        "SEARCH_AMAZON", "SEARCH_COUPANG", "SEARCH_MAPS_WEB", "TRANSLATE_TEXT", "SEARCH_NEWS", "SEARCH_SHOPPING",
    )

    private val templates = mapOf(
        "naver" to ("https://search.naver.com/search.naver?query=" to "Naver" ),
        "wikipedia" to ("https://ko.wikipedia.org/w/index.php?search=" to "Wikipedia"),
        "images" to ("https://www.google.com/search?tbm=isch&q=" to "Google Images"),
        "namu" to ("https://namu.wiki/Search?q=" to "Namuwiki"),
        "github" to ("https://github.com/search?q=" to "GitHub"),
        "amazon" to ("https://www.amazon.com/s?k=" to "Amazon"),
        "coupang" to ("https://www.coupang.com/np/search?q=" to "Coupang"),
        "news" to ("https://news.google.com/search?hl=ko&gl=KR&ceid=KR:ko&q=" to "Google News"),
        "shopping" to ("https://search.shopping.naver.com/search/all?query=" to "Naver Shopping"),
        "translate" to ("https://translate.google.com/?sl=auto&tl=en&op=translate&text=" to "Google Translate"),
        "maps_web" to ("https://www.google.com/maps/search/?api=1&query=" to "Google Maps"),
        "google" to ("https://www.google.com/search?q=" to "Google"),
    )

    override suspend fun execute(action: AiAction): ActionResult {
        val kind = action.kind()
        val site = if (kind == "SEARCH_SITE") (action.param("site") ?: "google").lowercase()
        else kind.removePrefix("SEARCH_").removePrefix("TRANSLATE_").lowercase().let { if (it == "text") "translate" else it }
        val (base, name) = templates[site] ?: templates.getValue("google")
        val query = action.param("query") ?: action.param("text") ?: return ActionResult.fail(
            "What should I search for?", "무엇을 검색할까요?",
        )
        return launcher.launch(Intent(Intent.ACTION_VIEW, Uri.parse(base + Uri.encode(query))), "$name: $query").toResult(
            ActionResult.fail("I couldn't open a browser.", "브라우저를 열 수 없습니다."),
        )
    }
}

class PlayStoreSearchCommand(private val launcher: ActivityLauncher) : Command {
    override val types = listOf("PLAY_STORE_SEARCH", "FIND_APP", "INSTALL_APP")

    override suspend fun execute(action: AiAction): ActionResult {
        val q = action.param("query") ?: action.param("app") ?: return ActionResult.fail("Which app?", "어떤 앱을 찾을까요?")
        val market = launcher.launch(Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=${Uri.encode(q)}&c=apps")), "Play Store: $q")
        if (market != ActivityLauncher.Outcome.FAILED) return market.toResult(ActionResult.unsupported())
        return launcher.launch(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/search?q=${Uri.encode(q)}&c=apps")), "Play Store: $q",
        ).toResult(ActionResult.fail("I couldn't open the store.", "스토어를 열 수 없습니다."))
    }
}

class AppSettingsCommand(
    private val resolver: AppResolver,
    private val launcher: ActivityLauncher,
) : Command {
    override val types = listOf("OPEN_APP_SETTINGS", "APP_INFO", "APP_PERMISSIONS")

    override suspend fun execute(action: AiAction): ActionResult {
        val app = resolver.resolve(action.param("package"), action.param("app")) ?: return ActionResult.fail(
            "I couldn't find that application.", "해당 앱을 찾을 수 없습니다.",
        )
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}"))
        return launcher.launch(intent, "${app.label} settings").toResult(
            ActionResult.fail("I couldn't open the app info.", "앱 정보를 열 수 없습니다."),
        )
    }
}

/** Plays a song / artist in whatever music app answers "play from search" (YouTube Music, Spotify ...). */
class MusicSearchCommand(private val launcher: ActivityLauncher) : Command {
    override val types = listOf("PLAY_MUSIC", "MUSIC_SEARCH", "PLAY_SONG", "PLAY_ARTIST")

    override suspend fun execute(action: AiAction): ActionResult {
        val q = action.param("query") ?: action.param("song") ?: action.param("artist") ?: return ActionResult.fail(
            "What should I play?", "어떤 음악을 재생할까요?",
        )
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            putExtra(SearchManager.QUERY, q)
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
        }
        val outcome = launcher.launch(intent, "Play: $q")
        if (outcome != ActivityLauncher.Outcome.FAILED) return outcome.toResult(ActionResult.unsupported())
        return launcher.launch(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/search?q=${Uri.encode(q)}")), "Music: $q",
        ).toResult(ActionResult.fail("I couldn't find a music app.", "음악 앱을 찾을 수 없습니다."))
    }
}

// ---------------------------------------------------------------------------------------------
// Sensors (one-shot readings)
// ---------------------------------------------------------------------------------------------

private suspend fun readOnce(manager: SensorManager, type: Int, timeoutMs: Long = 2_500): FloatArray? {
    val sensor = manager.getDefaultSensor(type) ?: return null
    return withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    manager.unregisterListener(this)
                    if (cont.isActive) cont.resume(event.values.clone())
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
            manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
            cont.invokeOnCancellation { manager.unregisterListener(listener) }
        }
    }
}

class AmbientLightCommand(context: Context) : Command {
    override val types = listOf("AMBIENT_LIGHT", "LIGHT_LEVEL", "LUX")
    override val executesBeforeSpeech = true
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    override suspend fun execute(action: AiAction): ActionResult {
        val v = readOnce(sensors, Sensor.TYPE_LIGHT) ?: return ActionResult.fail("This phone has no light sensor.", "이 기기에는 조도 센서가 없습니다.")
        val lux = v[0]
        val (en, ko) = when {
            lux < 10 -> "very dark" to "매우 어두움"
            lux < 100 -> "dim" to "어두운 편"
            lux < 1_000 -> "normal indoor lighting" to "보통 실내 밝기"
            lux < 10_000 -> "bright" to "밝음"
            else -> "very bright, likely direct sun" to "매우 밝음(직사광선 수준)"
        }
        return ActionResult.ok("The light level is ${lux.roundToInt()} lux, $en.", "주변 밝기는 ${lux.roundToInt()}럭스로 $ko 입니다.")
    }
}

class CompassCommand(context: Context) : Command {
    override val types = listOf("COMPASS", "HEADING", "DIRECTION")
    override val executesBeforeSpeech = true
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    override suspend fun execute(action: AiAction): ActionResult {
        val v = readOnce(sensors, Sensor.TYPE_ROTATION_VECTOR) ?: return ActionResult.fail(
            "This phone has no compass.", "이 기기에는 나침반 센서가 없습니다.",
        )
        val rot = FloatArray(9)
        val orient = FloatArray(3)
        SensorManager.getRotationMatrixFromVector(rot, v)
        SensorManager.getOrientation(rot, orient)
        val deg = ((Math.toDegrees(orient[0].toDouble()) + 360) % 360).roundToInt()
        val (en, ko) = headingName(deg)
        return ActionResult.ok("You are facing $en, at $deg degrees.", "현재 $ko 방향($deg°)을 향하고 있습니다.")
    }

    companion object {
        fun headingName(deg: Int): Pair<String, String> {
            val names = listOf(
                "north" to "북", "northeast" to "북동", "east" to "동", "southeast" to "남동",
                "south" to "남", "southwest" to "남서", "west" to "서", "northwest" to "북서",
            )
            return names[(((deg % 360) + 22.5) / 45).toInt() % 8]
        }
    }
}

class StepCountCommand(private val context: Context) : Command {
    override val types = listOf("STEP_COUNT", "STEPS", "PEDOMETER")
    override val executesBeforeSpeech = true
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    override suspend fun execute(action: AiAction): ActionResult {
        if (!Perms.hasActivityRecognition(context)) {
            return ActionResult.fail(
                "I need physical activity permission to count steps. You can allow it in settings.",
                "걸음 수를 세려면 신체 활동 권한이 필요합니다. 설정에서 허용해 주세요.",
            )
        }
        val v = readOnce(sensors, Sensor.TYPE_STEP_COUNTER) ?: return ActionResult.fail(
            "This phone has no step counter.", "이 기기에는 걸음 수 센서가 없습니다.",
        )
        val steps = v[0].toLong()
        return ActionResult.ok("About $steps steps since the phone last restarted.", "마지막 재부팅 이후 약 ${steps}걸음을 걸으셨습니다.")
    }
}

class AltitudeCommand(context: Context) : Command {
    override val types = listOf("ALTITUDE", "ELEVATION", "AIR_PRESSURE")
    override val executesBeforeSpeech = true
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    override suspend fun execute(action: AiAction): ActionResult {
        val v = readOnce(sensors, Sensor.TYPE_PRESSURE) ?: return ActionResult.fail(
            "This phone has no barometer.", "이 기기에는 기압 센서가 없습니다.",
        )
        val hpa = v[0]
        val alt = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, hpa).roundToInt()
        return ActionResult.ok(
            "Air pressure is ${hpa.roundToInt()} hectopascals, roughly $alt metres above sea level by the standard atmosphere.",
            "기압은 ${hpa.roundToInt()}hPa이며, 표준 대기 기준 고도는 약 ${alt}m입니다.",
        )
    }
}

package com.jarvis.assistant.command.commands

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import com.jarvis.assistant.ai.AiAction
import com.jarvis.assistant.command.ActionResult
import com.jarvis.assistant.command.Command
import com.jarvis.assistant.command.CommandRouter
import com.jarvis.assistant.data.model.AccentStyle
import com.jarvis.assistant.data.model.SettingKeys
import com.jarvis.assistant.data.model.ThemeMode
import com.jarvis.assistant.data.model.VoiceStyle
import com.jarvis.assistant.data.model.WakeSensitivity
import com.jarvis.assistant.data.repository.RoutineRepository
import com.jarvis.assistant.data.repository.SettingsRepository
import com.jarvis.assistant.speech.SpeechRecognizerEngine
import com.jarvis.assistant.util.NetworkMonitor
import com.jarvis.assistant.util.Perms

class HelpCommand : Command {
    override val types = listOf("HELP", "CAPABILITIES", "WHAT_CAN_YOU_DO")
    override val executesBeforeSpeech = true

    override suspend fun execute(action: AiAction): ActionResult = ActionResult.ok(
        "I can run your phone, set reminders and timers, do maths and conversions, fetch weather, news and prices, manage notes and lists, and run routines. Please see the subtitles for examples.",
        listOf(
            "• 시간·날씨·뉴스·미세먼지·일출  • 알람·타이머·알림·일정",
            "• 계산·단위/환율 변환·주사위·랜덤  • 메모·할 일·장보기 목록",
            "• 전화·문자·길안내·이메일  • 음악 재생/검색·볼륨·밝기",
            "• 손전등·SOS·내 폰 찾기  • 배터리·저장공간·네트워크 상태",
            "• 굿모닝/굿나잇 루틴·데일리 브리핑  • 에어 제스처",
        ).joinToString("\n"),
    )
}

/** "Say / repeat after me": speaks the given English text. */
class EchoCommand : Command {
    override val types = listOf("SAY", "ECHO", "REPEAT_AFTER_ME")
    override val executesBeforeSpeech = true

    override suspend fun execute(action: AiAction): ActionResult {
        val text = action.param("text") ?: return ActionResult.fail("What should I say?", "무엇을 말할까요?")
        return ActionResult.ok(text.take(400), action.param("subtitle") ?: text.take(400))
    }
}

/** JARVIS_SETTING {setting, value}: change the assistant itself by voice. */
class JarvisSettingCommand(private val settings: SettingsRepository) : Command {
    override val types = listOf("JARVIS_SETTING", "CHANGE_SETTING", "SET_OPTION")
    override val executesBeforeSpeech = true

    override suspend fun execute(action: AiAction): ActionResult {
        val setting = (action.param("setting") ?: "").lowercase().replace(' ', '_')
        val value = (action.param("value") ?: "").lowercase()
        val on = value in setOf("on", "true", "yes", "켜", "켜기", "enable", "enabled", "활성")
        val off = value in setOf("off", "false", "no", "꺼", "끄기", "disable", "disabled", "비활성")
        val current = settings.current()
        return when (setting) {
            "voice", "voice_feedback", "sound_off", "mute_jarvis" -> toggle(SettingKeys.VOICE_FEEDBACK, on, off, "Voice replies", "음성 응답")
            "subtitles", "subtitle" -> toggle(SettingKeys.SUBTITLES, on, off, "Subtitles", "자막")
            "auto_listen", "autolisten" -> toggle(SettingKeys.AUTO_LISTEN, on, off, "Auto listen", "자동 듣기")
            "haptic", "vibration" -> toggle(SettingKeys.WAKE_HAPTIC, on, off, "Haptic feedback", "햅틱 피드백")
            "ui_sounds", "sounds", "sound_effects" -> toggle(SettingKeys.UI_SOUNDS, on, off, "Interface sounds", "효과음")
            "wake_word", "wakeword" -> toggle(SettingKeys.WAKE_WORD_ENABLED, on, off, "The wake word", "호출어")
            "proximity_gestures", "wave_gesture" -> toggle(SettingKeys.PROXIMITY_GESTURES, on, off, "Wave gestures", "손 흔들기 제스처")
            "camera_gestures" -> toggle(SettingKeys.CAMERA_GESTURES, on, off, "Camera gestures", "카메라 제스처")
            "theme" -> {
                val t = ThemeMode.values().firstOrNull { it.name.equals(value, true) || (value == "밝게" && it == ThemeMode.LIGHT) || (value == "어둡게" && it == ThemeMode.DARK) }
                    ?: return ActionResult.fail("Choose dark, AMOLED or light.", "다크, 아몰레드, 라이트 중에서 골라 주세요.")
                settings.put(SettingKeys.THEME, t.name)
                ActionResult.ok("Theme set to ${t.label}.", "테마를 ${t.label}(으)로 바꿨습니다.")
            }
            "accent", "color", "hud_color" -> {
                val a = AccentStyle.values().firstOrNull { it.name.equals(value.replace(' ', '_'), true) || it.label.equals(value, true) }
                    ?: when (value) {
                        "blue", "cyan", "파랑", "파란색" -> AccentStyle.ARC_BLUE
                        "gold", "red", "골드", "금색", "빨강" -> AccentStyle.STARK_GOLD
                        "green", "초록", "녹색" -> AccentStyle.MATRIX_GREEN
                        "crimson", "진홍" -> AccentStyle.CRIMSON
                        else -> return ActionResult.fail("Choose blue, gold, green or crimson.", "파랑, 골드, 초록, 진홍 중에서 골라 주세요.")
                    }
                settings.put(SettingKeys.ACCENT, a.name)
                ActionResult.ok("HUD colour set to ${a.label}.", "HUD 색상을 ${a.label}(으)로 바꿨습니다.")
            }
            "voice_style", "style" -> {
                val v = VoiceStyle.values().firstOrNull { it.name.equals(value, true) || (value == "ai" && it == VoiceStyle.JARVIS) }
                    ?: return ActionResult.fail("Choose jarvis, natural or robotic.", "자비스, 자연스러운, 로봇 중에서 골라 주세요.")
                settings.put(SettingKeys.VOICE_STYLE, v.name)
                ActionResult.ok("Voice style set to ${v.label}.", "음성 스타일을 ${v.label}(으)로 바꿨습니다.")
            }
            "speed", "speech_speed", "rate" -> {
                val delta = when (value) {
                    "faster", "fast", "빠르게", "up" -> 0.1f
                    "slower", "slow", "느리게", "down" -> -0.1f
                    else -> null
                }
                val next = if (delta != null) (current.speechRate + delta).coerceIn(0.5f, 1.5f) else if (value == "normal" || value == "기본") 0.92f else null
                    ?: return ActionResult.fail("Say faster, slower or normal.", "빠르게, 느리게, 기본 중에서 말씀해 주세요.")
                settings.put(SettingKeys.SPEECH_RATE, next.toString())
                ActionResult.ok("Speaking at %.2f times speed.".format(next), "말하기 속도를 %.2f배로 설정했습니다.".format(next))
            }
            "wake_sensitivity", "sensitivity" -> {
                val s = WakeSensitivity.values().firstOrNull { it.name.equals(value, true) }
                    ?: when (value) {
                        "high", "높게", "민감" -> WakeSensitivity.SENSITIVE
                        "low", "낮게", "엄격" -> WakeSensitivity.STRICT
                        else -> WakeSensitivity.NORMAL
                    }
                settings.put(SettingKeys.WAKE_SENSITIVITY, s.name)
                ActionResult.ok("Wake sensitivity is ${s.label}.", "호출어 감도를 ${s.label}(으)로 설정했습니다.")
            }
            "title", "call_me", "name" -> {
                val title = action.param("value") ?: return ActionResult.fail("What should I call you?", "어떻게 불러드릴까요?")
                settings.put(SettingKeys.USER_TITLE, title.take(30))
                ActionResult.ok("Very well, I shall call you ${title.take(30)}.", "앞으로 ${title.take(30)}(이)라고 부르겠습니다.")
            }
            else -> ActionResult.fail("I can't change that setting by voice.", "해당 설정은 음성으로 바꿀 수 없습니다.")
        }
    }

    private suspend fun toggle(key: String, on: Boolean, off: Boolean, nameEn: String, nameKo: String): ActionResult {
        if (!on && !off) return ActionResult.fail("Should I turn it on or off?", "켤까요, 끌까요?")
        settings.put(key, on)
        return ActionResult.ok("$nameEn ${if (on) "enabled" else "disabled"}.", "$nameKo ${if (on) "켰습니다" else "껐습니다"}.")
    }
}

/** A JARVIS-style systems check using real data. */
class StatusReportCommand(
    private val context: Context,
    private val settings: SettingsRepository,
    private val network: NetworkMonitor,
    private val recognizer: SpeechRecognizerEngine,
    private val commandCount: () -> Int,
) : Command {
    override val types = listOf("STATUS_REPORT", "SYSTEM_STATUS", "DIAGNOSTICS", "SYSTEMS_CHECK")
    override val executesBeforeSpeech = true

    override suspend fun execute(action: AiAction): ActionResult {
        val s = settings.current()
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let {
            it.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        } ?: -1
        val stat = StatFs(Environment.getDataDirectory().path)
        val freeGb = stat.availableBytes / 1_073_741_824.0
        val aiReady = s.aiProvider.name == "OLLAMA" ||
            com.jarvis.assistant.data.model.AiProviderType.values().any { settings.hasAiApiKey(it) }
        val issues = mutableListOf<Pair<String, String>>()
        if (!Perms.hasMic(context)) issues += "the microphone is not permitted" to "마이크 권한 없음"
        if (!network.checkNow()) issues += "there is no internet connection" to "인터넷 연결 없음"
        if (!aiReady) issues += "the AI core has no API key" to "AI 키 미설정"
        if (!recognizer.isAvailable()) issues += "speech recognition is unavailable" to "음성 인식 사용 불가"
        if (battery in 0..15) issues += "battery is low" to "배터리 부족"
        val verdictEn = if (issues.isEmpty()) "All systems nominal." else "I found ${issues.size} issue${if (issues.size > 1) "s" else ""}: ${issues.joinToString("; ") { it.first }}."
        val verdictKo = if (issues.isEmpty()) "모든 시스템 정상입니다." else "문제 ${issues.size}건: ${issues.joinToString(", ") { it.second }}"
        return ActionResult.ok(
            "$verdictEn Battery $battery percent, ${"%.1f".format(freeGb)} gigabytes free, ${commandCount()} commands loaded.",
            "$verdictKo\n배터리 ${battery}% · 여유 저장공간 ${"%.1f".format(freeGb)}GB · 명령 ${commandCount()}개 로드",
        )
    }
}

/**
 * DAILY_BRIEFING: gathers several sub-commands and lets the AI weave them into one spoken briefing
 * (falls back to reading the pieces if the AI is unreachable).
 */
class BriefingCommand(private val router: () -> CommandRouter) : Command {
    override val types = listOf("DAILY_BRIEFING", "BRIEFING", "MORNING_BRIEFING")

    override suspend fun execute(action: AiAction): ActionResult {
        val steps = listOf("GET_TIME", "GET_DATE", "WEATHER", "CALENDAR_TODAY", "REMINDER_LIST", "TASK_LIST", "GET_BATTERY")
        val parts = mutableListOf<ActionResult>()
        for (type in steps) {
            val cmd = router().resolve(AiAction(type)) ?: continue
            val r = try {
                cmd.execute(AiAction(type, if (type == "WEATHER") mapOf("day" to "today") else emptyMap()))
            } catch (e: Exception) {
                continue
            }
            if (r.success) parts += r
        }
        if (parts.isEmpty()) return ActionResult.fail("I couldn't gather a briefing.", "브리핑을 만들지 못했습니다.")
        val data = parts.joinToString("\n") { (it.data ?: it.speech).orEmpty() }
        return ActionResult(
            success = true,
            speech = parts.mapNotNull { it.speech }.joinToString(" "),
            subtitle = parts.mapNotNull { it.subtitle }.joinToString("\n"),
            data = "Briefing material:\n$data",
            narrate = true,
        )
    }
}

class RoutineListCommand(private val repo: RoutineRepository) : Command {
    override val types = listOf("ROUTINE_LIST", "LIST_ROUTINES")
    override val executesBeforeSpeech = true

    override suspend fun execute(action: AiAction): ActionResult {
        val custom = repo.all().map { it.name }
        val builtIn = listOf("굿모닝", "굿나잇", "외출", "업무")
        return ActionResult.ok(
            "Available routines: good morning, good night, leaving home, work mode${if (custom.isNotEmpty()) ", and ${custom.size} of your own" else ""}.",
            "사용 가능한 루틴: ${(builtIn + custom).joinToString(", ")}",
        )
    }
}

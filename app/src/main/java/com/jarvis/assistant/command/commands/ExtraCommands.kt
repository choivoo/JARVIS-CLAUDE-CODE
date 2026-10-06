package com.jarvis.assistant.command.commands

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import com.jarvis.assistant.ai.AiAction
import com.jarvis.assistant.command.ActionResult
import com.jarvis.assistant.command.ActivityLauncher
import com.jarvis.assistant.command.AppResolver
import com.jarvis.assistant.command.Command
import com.jarvis.assistant.command.ContactResolver
import com.jarvis.assistant.data.repository.NoteRepository
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class SetTimerCommand(private val launcher: ActivityLauncher) : Command {
    override val types = listOf("SET_TIMER", "TIMER", "START_TIMER")

    override suspend fun execute(action: AiAction): ActionResult {
        fun n(name: String) = action.param(name)?.toDoubleOrNull() ?: 0.0
        val total = (n("hours") * 3600 + n("minutes") * 60 + n("seconds")).toInt()
        if (total !in 1..86_400) {
            return ActionResult.fail("How long should the timer run?", "타이머를 몇 분으로 맞출까요?")
        }
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, total)
            putExtra(AlarmClock.EXTRA_MESSAGE, action.param("label") ?: "JARVIS")
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
        return launcher.launch(intent, "Timer").toResult(
            ActionResult.fail("I couldn't find a clock app for the timer.", "타이머를 설정할 시계 앱을 찾을 수 없습니다."),
        )
    }
}

/** Opens the dialer prefilled (the user presses call), by number or by contact name. */
class CallCommand(private val launcher: ActivityLauncher, private val contacts: ContactResolver) : Command {
    override val types = listOf("CALL", "DIAL", "PHONE_CALL")

    override suspend fun execute(action: AiAction): ActionResult {
        val number = resolveNumber(action, contacts)
        return when (number) {
            is Target.Number -> launcher.launch(
                Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number.value)}")), "Call ${number.label}",
            ).toResult(ActionResult.fail("I couldn't open the dialer.", "전화 앱을 열 수 없습니다."))
            is Target.Failure -> number.result
        }
    }
}

/** Opens the messaging app with the text prefilled (the user presses send). */
class SendSmsCommand(private val launcher: ActivityLauncher, private val contacts: ContactResolver) : Command {
    override val types = listOf("SEND_SMS", "SEND_MESSAGE", "TEXT_MESSAGE")

    override suspend fun execute(action: AiAction): ActionResult {
        val target = resolveNumber(action, contacts)
        if (target is Target.Failure) return target.result
        target as Target.Number
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(target.value)}")).apply {
            putExtra("sms_body", (action.param("text") ?: action.param("message") ?: "").take(1_000))
        }
        return launcher.launch(intent, "Message ${target.label}").toResult(
            ActionResult.fail("I couldn't open the messaging app.", "문자 앱을 열 수 없습니다."),
        )
    }
}

private sealed interface Target {
    data class Number(val value: String, val label: String) : Target
    data class Failure(val result: ActionResult) : Target
}

private suspend fun resolveNumber(action: AiAction, contacts: ContactResolver): Target {
    action.param("number")?.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
        ?.takeIf { it.length >= 3 }?.let { return Target.Number(it, it) }
    val name = action.param("name") ?: action.param("contact")
        ?: return Target.Failure(ActionResult.fail("Who should I contact?", "누구에게 연락할까요?"))
    return when (val r = contacts.find(name)) {
        is ContactResolver.Result.Found -> Target.Number(r.number, r.name)
        ContactResolver.Result.NotFound -> Target.Failure(
            ActionResult.fail("I couldn't find that contact.", "연락처에서 찾을 수 없습니다."),
        )
        ContactResolver.Result.NoPermission -> Target.Failure(
            ActionResult.fail(
                "I need contacts permission to look people up. You can allow it in settings.",
                "연락처를 찾으려면 권한이 필요합니다. 설정에서 허용해 주세요.",
            ),
        )
    }
}

class NavigateCommand(
    private val resolver: AppResolver,
    private val launcher: ActivityLauncher,
) : Command {
    override val types = listOf("NAVIGATE", "DIRECTIONS", "MAP_SEARCH", "SHOW_MAP")

    override suspend fun execute(action: AiAction): ActionResult {
        val dest = action.param("destination") ?: action.param("query") ?: return ActionResult.fail(
            "Where would you like to go?", "어디로 갈까요?",
        )
        val navigate = action.type.uppercase().let { it.contains("NAVIGATE") || it.contains("DIRECTIONS") }
        val mapsInstalled = resolver.isInstalled(MAPS)
        val intent = if (navigate && mapsInstalled) {
            Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(dest)}")).setPackage(MAPS)
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(dest)}"))
        }
        return launcher.launch(intent, "Map: $dest").toResult(
            ActionResult.fail("I couldn't open a map app.", "지도 앱을 열 수 없습니다."),
        )
    }

    private companion object {
        const val MAPS = "com.google.android.apps.maps"
    }
}

class CreateEventCommand(private val launcher: ActivityLauncher) : Command {
    override val types = listOf("CREATE_EVENT", "ADD_EVENT", "CALENDAR_EVENT")

    override suspend fun execute(action: AiAction): ActionResult {
        val title = action.param("title") ?: return ActionResult.fail(
            "What should I call the event?", "일정 제목이 무엇인가요?",
        )
        val date = action.param("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now()
        val hour = action.param("hour")?.toDoubleOrNull()?.toInt() ?: 9
        val minutes = action.param("minutes")?.toDoubleOrNull()?.toInt() ?: 0
        if (hour !in 0..23 || minutes !in 0..59) {
            return ActionResult.fail("That time doesn't look right.", "시간이 올바르지 않습니다.")
        }
        val start = LocalDateTime.of(date, LocalTime.of(hour, minutes)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val duration = (action.param("duration_minutes")?.toDoubleOrNull() ?: 60.0).toLong().coerceIn(5, 24 * 60)
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title.take(120))
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start + duration * 60_000)
        }
        return launcher.launch(intent, "Event: $title").toResult(
            ActionResult.fail("I couldn't open a calendar app.", "캘린더 앱을 열 수 없습니다."),
        )
    }
}

class OpenCameraCommand(private val launcher: ActivityLauncher) : Command {
    override val types = listOf("OPEN_CAMERA", "TAKE_PHOTO", "CAMERA")

    override suspend fun execute(action: AiAction): ActionResult {
        val video = action.param("mode")?.lowercase() == "video"
        val intent = Intent(if (video) MediaStore.ACTION_VIDEO_CAPTURE else MediaStore.ACTION_IMAGE_CAPTURE)
        return launcher.launch(intent, "Camera").toResult(
            ActionResult.fail("I couldn't open the camera.", "카메라를 열 수 없습니다."),
        )
    }
}

class CopyTextCommand(private val context: Context) : Command {
    override val types = listOf("COPY_TEXT", "COPY_TO_CLIPBOARD")
    override val executesBeforeSpeech = true

    override suspend fun execute(action: AiAction): ActionResult {
        val text = action.param("text") ?: return ActionResult.fail("What should I copy?", "무엇을 복사할까요?")
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("JARVIS", text.take(5_000)))
        return ActionResult.ok("Copied to the clipboard.", "클립보드에 복사했습니다.")
    }
}

class ShareTextCommand(private val launcher: ActivityLauncher) : Command {
    override val types = listOf("SHARE_TEXT", "SHARE")

    override suspend fun execute(action: AiAction): ActionResult {
        val text = action.param("text") ?: return ActionResult.fail("What should I share?", "무엇을 공유할까요?")
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text.take(5_000))
        }
        return launcher.launch(Intent.createChooser(send, "JARVIS"), "Share").toResult(
            ActionResult.fail("I couldn't open the share sheet.", "공유 창을 열 수 없습니다."),
        )
    }
}

class SaveNoteCommand(private val notes: NoteRepository) : Command {
    override val types = listOf("NOTE_SAVE", "SAVE_NOTE", "REMEMBER", "ADD_NOTE")
    override val executesBeforeSpeech = true

    override suspend fun execute(action: AiAction): ActionResult {
        val text = action.param("text") ?: return ActionResult.fail(
            "What should I note down?", "무엇을 메모할까요?",
        )
        notes.add(text)
        return ActionResult.ok("Noted.", "메모했습니다.")
    }
}

class ListNotesCommand(private val notes: NoteRepository) : Command {
    override val types = listOf("NOTE_LIST", "LIST_NOTES", "READ_NOTES")

    override suspend fun execute(action: AiAction): ActionResult {
        val latest = notes.latest(5)
        if (latest.isEmpty()) return ActionResult.ok("You have no notes yet.", "저장된 메모가 없습니다.")
        val data = latest.mapIndexed { i, n -> "${i + 1}. ${n.text}" }.joinToString("\n")
        return ActionResult(
            success = true,
            speech = "You have ${latest.size} recent notes. The latest says: ${latest.first().text.take(160)}",
            subtitle = latest.joinToString("\n") { "• " + it.text.take(60) },
            data = "The user's most recent notes (newest first):\n$data",
            narrate = true,
        )
    }
}

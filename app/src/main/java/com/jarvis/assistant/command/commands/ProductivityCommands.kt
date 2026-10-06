package com.jarvis.assistant.command.commands

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import com.jarvis.assistant.ai.AiAction
import com.jarvis.assistant.command.ActionResult
import com.jarvis.assistant.command.ActivityLauncher
import com.jarvis.assistant.command.Command
import com.jarvis.assistant.data.repository.ReminderRepository
import com.jarvis.assistant.data.repository.SettingsRepository
import com.jarvis.assistant.data.repository.TaskRepository
import com.jarvis.assistant.reminder.ReminderScheduler
import com.jarvis.assistant.util.Perms
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// ---------------------------------------------------------------------------------------------
// Reminders (exact, survive reboots)
// ---------------------------------------------------------------------------------------------

class ReminderSetCommand(
    private val reminders: ReminderRepository,
    private val scheduler: ReminderScheduler,
) : Command {
    override val types = listOf("REMINDER_SET", "SET_REMINDER", "REMIND_ME", "ADD_REMINDER")
    override val executesBeforeSpeech = true

    override suspend fun execute(action: AiAction): ActionResult {
        val text = action.param("text") ?: action.param("label") ?: return ActionResult.fail(
            "What should I remind you about?", "무엇을 알려드릴까요?",
        )
        val at = triggerTime(action, LocalDateTime.now())
            ?: return ActionResult.fail("When should I remind you?", "언제 알려드릴까요?")
        if (at.isBefore(LocalDateTime.now())) {
            return ActionResult.fail("That time has already passed.", "이미 지난 시간입니다.")
        }
        val millis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val reminder = reminders.add(text, millis)
        scheduler.schedule(reminder)
        val whenEn = at.format(DateTimeFormatter.ofPattern("EEEE h:mm a", Locale.ENGLISH))
        val whenKo = at.format(DateTimeFormatter.ofPattern("M월 d일 a h시 mm분", Locale.KOREAN))
        return ActionResult.ok("Very well. I'll remind you on $whenEn.", "$whenKo 에 알려드리겠습니다: $text")
    }

    companion object {
        /** minutes_from_now / hours_from_now, or hour+minutes (+ optional date YYYY-MM-DD; past times roll to tomorrow). */
        fun triggerTime(action: AiAction, now: LocalDateTime): LocalDateTime? {
            val inMinutes = action.num("minutes_from_now") ?: action.num("in_minutes")
            val inHours = action.num("hours_from_now") ?: action.num("in_hours")
            if (inMinutes != null || inHours != null) {
                return now.plusSeconds((((inHours ?: 0.0) * 60 + (inMinutes ?: 0.0)) * 60).toLong())
            }
            val hour = action.num("hour")?.toInt() ?: return null
            val minute = action.num("minutes")?.toInt() ?: action.num("minute")?.toInt() ?: 0
            if (hour !in 0..23 || minute !in 0..59) return null
            val date = action.param("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val candidate = LocalDateTime.of(date ?: now.toLocalDate(), LocalTime.of(hour, minute))
            return if (date == null && !candidate.isAfter(now)) candidate.plusDays(1) else candidate
        }
    }
}

class ReminderListCommand(private val reminders: ReminderRepository) : Command {
    override val types = listOf("REMINDER_LIST", "LIST_REMINDERS", "SHOW_REMINDERS")

    override suspend fun execute(action: AiAction): ActionResult {
        val list = reminders.pending()
        if (list.isEmpty()) return ActionResult.ok("You have no pending reminders.", "예정된 알림이 없습니다.")
        val fmt = DateTimeFormatter.ofPattern("M/d HH:mm")
        fun at(ms: Long) = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), ZoneId.systemDefault()).format(fmt)
        val data = list.take(8).joinToString("\n") { "- ${at(it.triggerAt)}: ${it.text}" }
        return ActionResult(
            success = true,
            speech = "You have ${list.size} pending reminder${if (list.size > 1) "s" else ""}. Please see the subtitles.",
            subtitle = list.take(5).joinToString("\n") { "• ${at(it.triggerAt)}  ${it.text}" },
            data = "Pending reminders:\n$data",
            narrate = true,
        )
    }
}

class ReminderCancelCommand(
    private val reminders: ReminderRepository,
    private val scheduler: ReminderScheduler,
) : Command {
    override val types = listOf("REMINDER_CANCEL", "CANCEL_REMINDER", "CLEAR_REMINDERS")
    override val executesBeforeSpeech = true

    override suspend fun execute(action: AiAction): ActionResult {
        val list = reminders.pending()
        if (list.isEmpty()) return ActionResult.ok("There is nothing to cancel.", "취소할 알림이 없습니다.")
        val which = action.param("which")?.lowercase() ?: if (action.kind() == "CLEAR_REMINDERS") "all" else "next"
        val targets = when {
            which == "all" || which == "전체" -> list
            which == "next" || which == "다음" -> listOf(list.first())
            else -> list.filter { it.text.contains(which, ignoreCase = true) }.ifEmpty { listOf(list.first()) }
        }
        targets.forEach {
            scheduler.cancel(it.id)
            reminders.delete(it.id)
        }
        return ActionResult.ok(
            "Cancelled ${targets.size} reminder${if (targets.size > 1) "s" else ""}.",
            "알림 ${targets.size}개를 취소했습니다.",
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Tasks, shopping lists, counters
// ---------------------------------------------------------------------------------------------

class TaskCommand(private val tasks: TaskRepository) : Command {
    override val types = listOf(
        "TASK_ADD", "TODO_ADD", "ADD_TASK", "SHOPPING_ADD", "ADD_TO_LIST",
        "TASK_LIST", "TODO_LIST", "SHOPPING_LIST", "LIST_TASKS",
        "TASK_DONE", "COMPLETE_TASK", "TASK_CLEAR", "CLEAR_LIST",
    )

    override val executesBeforeSpeech = true

    override suspend fun execute(action: AiAction): ActionResult {
        val kind = action.kind()
        val list = TaskRepository.normalize(action.param("list") ?: if (kind.startsWith("SHOPPING")) "shopping" else "todo")
        val nameEn = if (list == "shopping") "shopping list" else if (list == "todo") "to-do list" else "$list list"
        val nameKo = if (list == "shopping") "장보기 목록" else if (list == "todo") "할 일 목록" else "$list 목록"
        return when {
            kind.contains("ADD") -> {
                val text = action.param("text") ?: return ActionResult.fail("What should I add?", "무엇을 추가할까요?")
                // "우유, 계란, 빵" -> three items for a shopping list
                val items = if (list == "shopping") text.split(',', '，', '、').map { it.trim() }.filter { it.isNotEmpty() } else listOf(text)
                items.forEach { tasks.add(list, it) }
                ActionResult.ok("Added to your $nameEn.", "$nameKo 에 ${items.size}개 추가했습니다: ${items.joinToString(", ")}")
            }
            kind.contains("LIST") && !kind.contains("CLEAR") -> {
                val open = tasks.open(list)
                if (open.isEmpty()) ActionResult.ok("Your $nameEn is empty.", "$nameKo 이 비어 있습니다.")
                else ActionResult(
                    success = true,
                    speech = "You have ${open.size} item${if (open.size > 1) "s" else ""} on your $nameEn. Please see the subtitles.",
                    subtitle = open.take(8).mapIndexed { i, t -> "${i + 1}. ${t.text}" }.joinToString("\n"),
                    data = "Open items on the $nameEn:\n" + open.mapIndexed { i, t -> "${i + 1}. ${t.text}" }.joinToString("\n"),
                    narrate = true,
                )
            }
            kind.contains("DONE") || kind.contains("COMPLETE") -> {
                val match = action.param("text") ?: action.param("index") ?: return ActionResult.fail("Which item is done?", "어떤 항목을 완료할까요?")
                val done = tasks.complete(list, match)
                if (done != null) ActionResult.ok("Marked as done.", "완료 처리했습니다: ${done.text}")
                else ActionResult.fail("I couldn't find that item.", "해당 항목을 찾을 수 없습니다.")
            }
            else -> {
                tasks.clearList(list)
                ActionResult.ok("Your $nameEn is cleared.", "$nameKo 을 비웠습니다.")
            }
        }
    }
}

/** COUNTER_ADD {name, amount} / COUNTER_GET {name}: daily tallies such as water or coffee. */
class CounterCommand(private val settings: SettingsRepository) : Command {
    override val types = listOf("COUNTER_ADD", "COUNTER_GET", "COUNT_UP", "COUNTER")
    override val executesBeforeSpeech = true

    override suspend fun execute(action: AiAction): ActionResult {
        val name = (action.param("name") ?: "count").lowercase().take(30)
        val key = "counter.$name.${LocalDate.now()}"
        val current = settings.getRaw(key)?.toIntOrNull() ?: 0
        if (action.kind() == "COUNTER_GET" || action.kind() == "COUNTER") {
            return ActionResult.ok("$name count today is $current.", "오늘 $name 횟수는 ${current}회입니다.")
        }
        val amount = action.num("amount")?.toInt() ?: 1
        val now = (current + amount).coerceAtLeast(0)
        settings.put(key, now.toString())
        return ActionResult.ok("Logged. That's $now for $name today.", "기록했습니다. 오늘 $name ${now}회째입니다.")
    }
}

// ---------------------------------------------------------------------------------------------
// Email, calendar, alarms & timers views
// ---------------------------------------------------------------------------------------------

class EmailCommand(private val launcher: ActivityLauncher) : Command {
    override val types = listOf("EMAIL_COMPOSE", "SEND_EMAIL", "COMPOSE_EMAIL")

    override suspend fun execute(action: AiAction): ActionResult {
        val to = action.param("to") ?: ""
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Uri.encode(to)}")).apply {
            putExtra(Intent.EXTRA_SUBJECT, (action.param("subject") ?: "").take(200))
            putExtra(Intent.EXTRA_TEXT, (action.param("body") ?: action.param("text") ?: "").take(5_000))
        }
        return launcher.launch(intent, "Email").toResult(ActionResult.fail("I couldn't open an email app.", "이메일 앱을 열 수 없습니다."))
    }
}

/** CALENDAR_TODAY / CALENDAR_TOMORROW / CALENDAR_NEXT. Needs the optional READ_CALENDAR permission. */
class CalendarReadCommand(private val context: Context) : Command {
    override val types = listOf("CALENDAR_TODAY", "CALENDAR_TOMORROW", "CALENDAR_NEXT", "SCHEDULE", "TODAY_SCHEDULE", "NEXT_EVENT")

    override suspend fun execute(action: AiAction): ActionResult {
        if (!Perms.hasCalendar(context)) {
            return ActionResult.fail(
                "I need calendar permission to read your schedule. You can allow it in settings.",
                "일정을 읽으려면 캘린더 권한이 필요합니다. 설정에서 허용해 주세요.",
            )
        }
        val kind = action.kind()
        val zone = ZoneId.systemDefault()
        val day = LocalDate.now().plusDays(if (kind == "CALENDAR_TOMORROW") 1 else 0)
        val start = if (kind == "CALENDAR_NEXT" || kind == "NEXT_EVENT") System.currentTimeMillis()
        else day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = if (kind == "CALENDAR_NEXT" || kind == "NEXT_EVENT") start + 14L * 86_400_000
        else day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val events = withContext(Dispatchers.IO) {
            val out = mutableListOf<Pair<Long, String>>()
            val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
            android.content.ContentUris.appendId(builder, start)
            android.content.ContentUris.appendId(builder, end)
            context.contentResolver.query(
                builder.build(),
                arrayOf(CalendarContract.Instances.BEGIN, CalendarContract.Instances.TITLE),
                null, null, "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { c ->
                while (c.moveToNext()) out += c.getLong(0) to (c.getString(1) ?: "(no title)")
            }
            out
        }
        val label = if (kind == "CALENDAR_TOMORROW") "tomorrow" else if (kind.contains("NEXT")) "coming up" else "today"
        val labelKo = if (kind == "CALENDAR_TOMORROW") "내일" else if (kind.contains("NEXT")) "다가오는" else "오늘"
        if (events.isEmpty()) return ActionResult.ok("You have nothing scheduled $label.", "$labelKo 일정이 없습니다.")
        val shown = if (kind.contains("NEXT")) events.take(1) else events.take(6)
        val fmt = DateTimeFormatter.ofPattern("M/d HH:mm")
        fun at(ms: Long) = java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), zone).format(fmt)
        return ActionResult(
            success = true,
            speech = "You have ${events.size} event${if (events.size > 1) "s" else ""} $label. Please see the subtitles.",
            subtitle = shown.joinToString("\n") { "• ${at(it.first)}  ${it.second}" },
            data = "Calendar events ($label):\n" + shown.joinToString("\n") { "- ${at(it.first)}: ${it.second}" },
            narrate = true,
        )
    }
}

class ShowAlarmsCommand(private val launcher: ActivityLauncher) : Command {
    override val types = listOf("SHOW_ALARMS", "LIST_ALARMS", "SHOW_TIMERS")

    override suspend fun execute(action: AiAction): ActionResult {
        val timers = action.kind() == "SHOW_TIMERS"
        return launcher.launch(
            Intent(if (timers) AlarmClock.ACTION_SHOW_TIMERS else AlarmClock.ACTION_SHOW_ALARMS), if (timers) "Timers" else "Alarms",
        ).toResult(ActionResult.fail("I couldn't open the clock app.", "시계 앱을 열 수 없습니다."))
    }
}

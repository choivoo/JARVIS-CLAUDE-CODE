package com.jarvis.assistant.command.commands

import android.content.Intent
import android.provider.AlarmClock
import com.jarvis.assistant.ai.AiAction
import com.jarvis.assistant.command.ActionResult
import com.jarvis.assistant.command.ActivityLauncher
import com.jarvis.assistant.command.Command

class SetAlarmCommand(private val launcher: ActivityLauncher) : Command {
    override val types = listOf("SET_ALARM", "ALARM", "CREATE_ALARM")

    override suspend fun execute(action: AiAction): ActionResult {
        val hour = action.param("hour")?.toDoubleOrNull()?.toInt()
        val minutes = action.param("minutes")?.toDoubleOrNull()?.toInt()
            ?: action.param("minute")?.toDoubleOrNull()?.toInt()
            ?: 0
        if (hour == null || hour !in 0..23 || minutes !in 0..59) {
            return ActionResult.fail(
                "I couldn't work out the time for that alarm. Could you repeat it?",
                "알람 시간을 이해하지 못했습니다. 다시 말씀해 주시겠어요?",
            )
        }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minutes)
            putExtra(AlarmClock.EXTRA_MESSAGE, action.param("label") ?: "JARVIS")
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
        return launcher.launch(intent, "Alarm %02d:%02d".format(hour, minutes)).toResult(
            ActionResult.fail("I couldn't find a clock app to set the alarm.", "알람을 설정할 시계 앱을 찾을 수 없습니다."),
        )
    }
}

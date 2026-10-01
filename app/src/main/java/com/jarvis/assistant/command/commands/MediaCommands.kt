package com.jarvis.assistant.command.commands

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.view.KeyEvent
import com.jarvis.assistant.ai.AiAction
import com.jarvis.assistant.command.ActionResult
import com.jarvis.assistant.command.ActivityLauncher
import com.jarvis.assistant.command.AppResolver
import com.jarvis.assistant.command.Command
import kotlinx.coroutines.delay

/**
 * Transport controls through media key events, which Android routes to the active MediaSession
 * (YouTube Music, Spotify, ...). When nothing is playing, "play" opens a music app instead.
 */
class MusicCommand(
    private val context: Context,
    private val resolver: AppResolver,
    private val launcher: ActivityLauncher,
) : Command {
    override val types = listOf(
        "MUSIC_PLAY", "MUSIC_PAUSE", "MUSIC_NEXT", "MUSIC_PREVIOUS",
        "PLAY", "PAUSE", "NEXT", "PREVIOUS", "MEDIA_PLAY", "MEDIA_PAUSE", "MEDIA_NEXT", "MEDIA_PREVIOUS",
    )
    override val executesBeforeSpeech = true

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override suspend fun execute(action: AiAction): ActionResult {
        val type = action.type.uppercase().removePrefix("MUSIC_").removePrefix("MEDIA_")
        val key = when (type) {
            "PLAY" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "PAUSE" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "NEXT" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "PREVIOUS" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return ActionResult.unsupported()
        }
        val wasActive = audio.isMusicActive
        send(key)
        if (key == KeyEvent.KEYCODE_MEDIA_PLAY) {
            delay(600)
            if (!audio.isMusicActive && !wasActive) {
                val app = resolver.resolve(AppResolver.YOUTUBE_MUSIC, "music")
                    ?: return ActionResult.fail(
                        "I couldn't find a music player.",
                        "음악 재생 앱을 찾을 수 없습니다.",
                    )
                launcher.launch(app.launchIntent, app.label)
                return ActionResult.ok("Opening ${app.label}.", "${app.label}을(를) 엽니다.")
            }
            return ActionResult.ok("Playing.", "재생합니다.")
        }
        return when (key) {
            KeyEvent.KEYCODE_MEDIA_PAUSE -> ActionResult.ok("Paused.", "일시정지했습니다.")
            KeyEvent.KEYCODE_MEDIA_NEXT -> ActionResult.ok("Next track.", "다음 곡으로 넘깁니다.")
            else -> ActionResult.ok("Previous track.", "이전 곡으로 돌아갑니다.")
        }
    }

    private fun send(keyCode: Int) {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }
}

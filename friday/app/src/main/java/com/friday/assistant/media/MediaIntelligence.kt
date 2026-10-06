package com.friday.assistant.media

import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.view.KeyEvent
import com.friday.assistant.command.CardKind
import com.friday.assistant.command.CommandExecutor
import com.friday.assistant.command.CommandResult
import com.friday.assistant.command.CommandType
import com.friday.assistant.command.InfoCard
import com.friday.assistant.notification.NotificationExecutors
import com.friday.assistant.notification.NotificationHub
import kotlinx.coroutines.delay

enum class MediaAction { PLAY, PAUSE, NEXT, PREVIOUS, STOP }

data class MediaSessionInfo(val packageName: String, val appLabel: String, val title: String, val artist: String, val playing: Boolean)

/** Everything media commands need from Android, so the logic can be tested without a device. */
interface MediaBackend {
    /** True when MediaSessions can be listed (needs Notification Access). */
    fun hasSessionAccess(): Boolean
    /** Best active session (playing first), or null. */
    fun activeSession(): MediaSessionInfo?
    /** Sends the action through the session's transport controls. False if there is no controllable session. */
    fun transport(action: MediaAction): Boolean
    /** Fallback: a global media key event; Android gives no result. */
    fun mediaKey(action: MediaAction)
    fun isMusicActive(): Boolean
}

class AndroidMediaBackend(private val context: Context) : MediaBackend {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override fun hasSessionAccess() = NotificationHub.isAccessGranted(context)

    private fun controllers(): List<MediaController> {
        if (!hasSessionAccess()) return emptyList()
        return try {
            context.getSystemService(MediaSessionManager::class.java)
                .getActiveSessions(NotificationHub.componentName(context))
        } catch (e: SecurityException) { emptyList() }
    }

    private fun best(): MediaController? {
        val all = controllers()
        return all.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: all.firstOrNull()
    }

    override fun activeSession(): MediaSessionInfo? {
        val c = best() ?: return null
        val md = c.metadata
        val pm = context.packageManager
        val label = try { pm.getApplicationLabel(pm.getApplicationInfo(c.packageName, 0)).toString() } catch (e: Exception) { c.packageName }
        return MediaSessionInfo(
            c.packageName, label,
            md?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            (md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)).orEmpty(),
            c.playbackState?.state == PlaybackState.STATE_PLAYING,
        )
    }

    override fun transport(action: MediaAction): Boolean {
        val c = best() ?: return false
        val t = c.transportControls
        when (action) {
            MediaAction.PLAY -> t.play(); MediaAction.PAUSE -> t.pause(); MediaAction.NEXT -> t.skipToNext()
            MediaAction.PREVIOUS -> t.skipToPrevious(); MediaAction.STOP -> t.stop()
        }
        return true
    }

    override fun mediaKey(action: MediaAction) {
        val code = when (action) {
            MediaAction.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY; MediaAction.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
            MediaAction.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT; MediaAction.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            MediaAction.STOP -> KeyEvent.KEYCODE_MEDIA_STOP
        }
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    override fun isMusicActive() = audio.isMusicActive
}

/** Media commands. Uses the real MediaSession when allowed, media keys otherwise, and never claims unconfirmed success. */
class MediaExecutors(private val backend: MediaBackend, private val settleMs: Long = 350) {
    fun all(): Map<CommandType, CommandExecutor> = mapOf(
        CommandType.PLAY_MEDIA to CommandExecutor { _, _ -> control(MediaAction.PLAY) },
        CommandType.PAUSE_MEDIA to CommandExecutor { _, _ -> control(MediaAction.PAUSE) },
        CommandType.NEXT_MEDIA to CommandExecutor { _, _ -> control(MediaAction.NEXT) },
        CommandType.PREVIOUS_MEDIA to CommandExecutor { _, _ -> control(MediaAction.PREVIOUS) },
        CommandType.STOP_MEDIA to CommandExecutor { _, _ -> control(MediaAction.STOP) },
        CommandType.GET_MEDIA_STATE to CommandExecutor { _, _ -> state() },
    )

    private suspend fun control(a: MediaAction): CommandResult {
        val viaSession = backend.transport(a)
        if (!viaSession) backend.mediaKey(a)
        delay(settleMs)
        val active = backend.isMusicActive()
        return when {
            (a == MediaAction.PAUSE || a == MediaAction.STOP) && active ->
                CommandResult.failed("I sent ${a.name.lowercase()}, but audio is still playing.", "신호를 보냈지만 소리가 계속 재생 중입니다.")
            a == MediaAction.PLAY && !active ->
                CommandResult.failed("I sent play, but nothing started. Open a music app first.", "재생 신호를 보냈지만 시작되지 않았습니다. 먼저 음악 앱을 실행해 주세요.")
            else -> CommandResult.ok(
                when (a) { MediaAction.PLAY -> "Playing."; MediaAction.PAUSE -> "Paused."; MediaAction.NEXT -> "Next track."; MediaAction.PREVIOUS -> "Previous track."; MediaAction.STOP -> "Stopped." },
                when (a) { MediaAction.PLAY -> "재생합니다."; MediaAction.PAUSE -> "일시정지했습니다."; MediaAction.NEXT -> "다음 곡으로 넘깁니다."; MediaAction.PREVIOUS -> "이전 곡으로 돌아갑니다."; MediaAction.STOP -> "재생을 멈췄습니다." },
            )
        }
    }

    private fun state(): CommandResult {
        if (!backend.hasSessionAccess()) {
            return if (backend.isMusicActive()) CommandResult.ok(
                "Something is playing, but I can't see what without Notification Access.",
                "재생 중인 소리가 있지만 곡 정보를 보려면 알림 접근 권한이 필요합니다.",
            ) else CommandResult.ok("Nothing seems to be playing.", "재생 중인 미디어가 없는 것 같습니다.")
        }
        val s = backend.activeSession() ?: return CommandResult.ok("Nothing is playing right now.", "지금 재생 중인 미디어가 없습니다.")
        val app = NotificationExecutors.speakable(s.appLabel)
        val title = s.title.ifBlank { "an unknown track" }
        val speakTitle = NotificationExecutors.isMostlyLatin(title)
        val speech = (if (s.playing) "Now playing " else "Paused: ") + (if (speakTitle) "$title${if (s.artist.isNotBlank() && NotificationExecutors.isMostlyLatin(s.artist)) " by ${s.artist}" else ""}" else "a track") + " on $app."
        return CommandResult.ok(
            speech,
            "${if (s.playing) "재생 중" else "일시정지"}: ${s.title.ifBlank { "알 수 없는 곡" }}${if (s.artist.isNotBlank()) " — ${s.artist}" else ""} (${s.appLabel})",
            card = InfoCard(CardKind.MEDIA, s.title.ifBlank { "알 수 없는 곡" }, listOf(s.artist, s.appLabel).filter { it.isNotBlank() }),
        )
    }
}

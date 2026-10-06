package com.friday.assistant.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import com.friday.assistant.settings.AudioFocusMode

/** Takes and gives back audio focus around FRIDAY's speech. */
interface FocusController {
    /** Returns true when focus was granted (or not needed). [onLost] fires if the system takes focus away (e.g. a call). */
    fun acquire(mode: AudioFocusMode, onLost: () -> Unit = {}): Boolean
    fun release()
}

class AudioFocusManager(context: Context) : FocusController {
    private val audio = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var request: AudioFocusRequest? = null

    override fun acquire(mode: AudioFocusMode, onLost: () -> Unit): Boolean {
        if (mode == AudioFocusMode.OFF) return true
        release()
        val gain = if (mode == AudioFocusMode.DUCK) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        val req = AudioFocusRequest.Builder(gain)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) onLost()
            }
            .build()
        request = req
        return audio.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    override fun release() {
        request?.let { audio.abandonAudioFocusRequest(it) }
        request = null
    }
}

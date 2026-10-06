package com.friday.assistant.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.friday.assistant.settings.FridaySettings

/**
 * Shows FRIDAY's Korean subtitle above every other app (TYPE_APPLICATION_OVERLAY) while FRIDAY itself is in the
 * background. It is a touch-through, non-focusable text bubble, never shown unless the user granted "Display over
 * other apps" and left the option on, and it disappears when the subtitle ends or FRIDAY comes to the front.
 */
class SubtitleOverlay(
    private val context: Context,
    private val settings: () -> FridaySettings,
    private val isForeground: () -> Boolean,
    private val canDraw: () -> Boolean = { Settings.canDrawOverlays(context) },
    private val wm: WindowManager = context.getSystemService(WindowManager::class.java),
) {
    private val main = Handler(Looper.getMainLooper())
    private var view: TextView? = null
    private var lastText = ""
    @Volatile var lastError: String? = null
        private set

    val isShowing: Boolean get() = view != null

    /** Call whenever the subtitle changes. Safe from any thread. */
    fun update(text: String) { lastText = text; main.post { apply() } }

    /** Re-evaluate after the app moved between foreground and background or a setting changed. */
    fun refresh() { main.post { apply() } }

    private fun apply() {
        val s = settings()
        if (shouldShow(s.overlaySubtitles, canDraw(), isForeground(), lastText)) show(lastText, s.subtitleScale) else hide()
    }

    private fun show(text: String, scale: Float) {
        try {
            val v = view ?: TextView(context).apply {
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                val d = context.resources.displayMetrics.density
                setPadding((18 * d).toInt(), (10 * d).toInt(), (18 * d).toInt(), (10 * d).toInt())
                background = GradientDrawable().apply {
                    setColor(Color.argb(215, 6, 4, 16)); cornerRadius = 18 * d; setStroke((1 * d).toInt().coerceAtLeast(1), Color.argb(170, 178, 107, 255))
                }
                maxWidth = (context.resources.displayMetrics.widthPixels * 0.9).toInt()
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                alpha = 0f
            }
            v.textSize = 18f * scale
            v.text = text
            if (view == null) {
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT,
                ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = (110 * context.resources.displayMetrics.density).toInt() }
                wm.addView(v, lp)
                view = v
            }
            v.animate().alpha(1f).setDuration(150).start()
            lastError = null
        } catch (e: Exception) {
            // Permission revoked or the system refused: never crash the assistant over a subtitle.
            lastError = e.javaClass.simpleName
            view = null
        }
    }

    private fun hide() {
        val v = view ?: return
        view = null
        v.animate().alpha(0f).setDuration(200).withEndAction { runCatching { wm.removeView(v) } }.start()
        // If animations never run (e.g. the view is detached) make sure it still goes away.
        main.postDelayed({ runCatching { wm.removeView(v) } }, 400)
    }

    companion object {
        fun shouldShow(enabled: Boolean, canDraw: Boolean, appInForeground: Boolean, text: String) =
            enabled && canDraw && !appInForeground && text.isNotBlank()
    }
}

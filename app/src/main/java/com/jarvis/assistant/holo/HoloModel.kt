package com.jarvis.assistant.holo

import kotlin.math.ceil
import kotlin.math.min

/** The windows the holographic workspace can show (4 to 11 at once). */
enum class HoloPanel(val title: String, val ko: String, val keywords: List<String>) {
    WEATHER("WEATHER", "날씨", listOf("날씨", "weather")),
    AIR("AIR · SUN", "대기·일출", listOf("대기", "미세먼지", "공기", "자외선", "일출", "air", "uv", "sun")),
    CALENDAR("SCHEDULE", "일정", listOf("일정", "캘린더", "스케줄", "calendar", "schedule")),
    TASKS("TASKS", "할 일", listOf("할 일", "할일", "투두", "장보기", "task", "todo", "list")),
    NOTES("NOTES", "메모", listOf("메모", "노트", "note")),
    REMINDERS("REMINDERS", "알림", listOf("알림", "리마인더", "reminder")),
    SYSTEM("SYSTEM", "시스템", listOf("시스템", "기기", "배터리", "상태", "system", "battery")),
    NEWS("NEWS", "뉴스", listOf("뉴스", "news")),
    MARKET("MARKET", "시세", listOf("시세", "코인", "비트코인", "market", "crypto")),
    MUSIC("MUSIC", "음악", listOf("음악", "뮤직", "music", "player")),
    CLOCK("WORLD CLOCK", "세계 시계", listOf("시계", "세계", "시간", "clock", "world"));

    companion object {
        /** Default order when the user has not chosen panels: the most useful ones first. */
        val DEFAULT_ORDER = listOf(WEATHER, CALENDAR, TASKS, SYSTEM, NEWS, REMINDERS, AIR, MUSIC, NOTES, MARKET, CLOCK)
        const val MIN_OPEN = 4
        const val MAX_OPEN = 11

        fun fromSpoken(text: String): HoloPanel? {
            val t = text.lowercase()
            return values().firstOrNull { p -> p.name.lowercase() == t.trim() }
                ?: values().firstOrNull { p -> p.keywords.any { t.contains(it) } }
        }
    }
}

/** Position and size are fractions of the workspace (0..1). */
data class HoloWindow(
    val panel: HoloPanel,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val z: Int = 0,
    val maximized: Boolean = false,
    /** Saved placement while maximised. */
    val restore: List<Float>? = null,
)

data class FRect(val l: Float, val t: Float, val r: Float, val b: Float) {
    val w get() = r - l
    val h get() = b - t
    fun contains(px: Float, py: Float) = px in l..r && py in t..b
    fun overlaps(o: FRect) = l < o.r && o.l < r && t < o.b && o.t < b
}

object HoloLayout {
    /** Usable band of the screen: below the title bar, above the toolbar. */
    const val TOP = 0.13f
    const val BOTTOM = 0.84f
    private const val SIDE = 0.03f
    private const val GAP = 0.02f

    /**
     * Places [panels] on a floating grid that fills the workspace.
     * Few windows are large, many windows are small; nothing overlaps.
     */
    fun arrange(panels: List<HoloPanel>): List<HoloWindow> {
        val n = panels.size
        if (n == 0) return emptyList()
        val cols = when {
            n <= 1 -> 1
            n <= 8 -> 2
            else -> 3
        }
        val rows = ceil(n / cols.toFloat()).toInt()
        val width = (1f - 2 * SIDE - (cols - 1) * GAP) / cols
        val pitch = (BOTTOM - TOP) / rows
        val height = (pitch - GAP).coerceAtLeast(0.05f)
        // A little vertical float on alternate columns gives depth without causing overlap.
        val stagger = min(0.012f, (pitch - height) / 2f)
        return panels.mapIndexed { i, p ->
            val col = i % cols
            val row = i / cols
            HoloWindow(
                panel = p,
                x = SIDE + col * (width + GAP),
                y = TOP + row * pitch + if (col % 2 == 1) stagger else 0f,
                w = width,
                h = height,
                z = i,
            )
        }
    }

    /** Which panels to open for a given count when the user has no explicit selection. */
    fun defaultPanels(count: Int): List<HoloPanel> =
        HoloPanel.DEFAULT_ORDER.take(count.coerceIn(HoloPanel.MIN_OPEN, HoloPanel.MAX_OPEN))

    fun maximizedRect() = floatArrayOf(0.04f, TOP, 0.92f, BOTTOM - TOP - 0.02f)
}

/** What a window shows. */
data class HoloAction(val id: String, val label: String)

data class HoloContent(
    val status: Status = Status.LOADING,
    val big: String? = null,
    val sub: String? = null,
    val lines: List<String> = emptyList(),
    val actions: List<HoloAction> = emptyList(),
    val note: String? = null,
) {
    enum class Status { LOADING, READY, EMPTY, ERROR }

    /** One compact paragraph for the AI when the user points at this window and speaks. */
    fun forAi(panel: HoloPanel): String = buildString {
        append("${panel.title} window")
        big?.let { append(" shows $it") }
        sub?.let { append(" ($it)") }
        if (lines.isNotEmpty()) append(": ").append(lines.take(8).joinToString("; "))
        if (status == Status.EMPTY) append(" (empty)")
        if (status == Status.ERROR) append(" (could not load)")
        note?.let { append(". $it") }
    }.take(700)
}

/** Pure drag / click logic for the workspace; the same code serves finger touches and the air cursor. */
class HoloInteraction(
    private val windows: () -> List<HoloWindow>,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun focus(panel: HoloPanel?)
        fun move(panel: HoloPanel, x: Float, y: Float)
        fun close(panel: HoloPanel)
        fun toggleMaximize(panel: HoloPanel)
        fun refresh(panel: HoloPanel)
        fun action(panel: HoloPanel, actionId: String)
        fun bringToFront(panel: HoloPanel)
        fun hover(panel: HoloPanel?, x: Float, y: Float)
    }

    var actionsFor: (HoloPanel) -> List<HoloAction> = { emptyList() }

    private var dragging: HoloPanel? = null
    private var grabDx = 0f
    private var grabDy = 0f
    private var downAt: Pair<Float, Float>? = null
    private var moved = false

    fun rectOf(w: HoloWindow) = FRect(w.x, w.y, w.x + w.w, w.y + w.h)

    /** Topmost window under the point. */
    fun hit(x: Float, y: Float): HoloWindow? =
        windows().filter { rectOf(it).contains(x, y) }.maxByOrNull { it.z }

    fun onHover(x: Float, y: Float) {
        val h = hit(x, y)?.panel
        callbacks.hover(h, x, y)
    }

    fun onDown(x: Float, y: Float) {
        val w = hit(x, y)
        downAt = x to y
        moved = false
        if (w == null) {
            callbacks.focus(null)
            return
        }
        callbacks.focus(w.panel)
        callbacks.bringToFront(w.panel)
        dragging = w.panel.takeIf { !w.maximized }
        grabDx = x - w.x
        grabDy = y - w.y
    }

    fun onMove(x: Float, y: Float) {
        callbacks.hover(hit(x, y)?.panel, x, y)
        val d = dragging ?: return
        val start = downAt ?: return
        if (!moved && kotlin.math.hypot(x - start.first, y - start.second) < SLOP) return
        moved = true
        val w = windows().firstOrNull { it.panel == d } ?: return
        val nx = (x - grabDx).coerceIn(0f, 1f - w.w)
        val ny = (y - grabDy).coerceIn(0f, 1f - w.h)
        callbacks.move(d, nx, ny)
    }

    /** Returns true if the release counted as a click rather than the end of a drag. */
    fun onUp(x: Float, y: Float): Boolean {
        val wasDragging = dragging != null && moved
        dragging = null
        downAt = null
        if (wasDragging) return false
        val w = hit(x, y) ?: return false
        click(w, x, y)
        return true
    }

    private fun click(w: HoloWindow, x: Float, y: Float) {
        val r = rectOf(w)
        val inHeader = y < r.t + r.h * HEADER
        val inClose = inHeader && x > r.r - r.w * CLOSE
        val actions = actionsFor(w.panel)
        val inActions = actions.isNotEmpty() && y > r.b - r.h * ACTION_BAND
        when {
            inClose -> callbacks.close(w.panel)
            inActions -> {
                val index = (((x - r.l) / r.w) * actions.size).toInt().coerceIn(0, actions.size - 1)
                callbacks.action(w.panel, actions[index].id)
            }
            inHeader -> callbacks.refresh(w.panel)
            else -> callbacks.toggleMaximize(w.panel)
        }
    }

    companion object {
        const val SLOP = 0.012f
        const val HEADER = 0.18f
        const val CLOSE = 0.16f
        const val ACTION_BAND = 0.28f
    }

}

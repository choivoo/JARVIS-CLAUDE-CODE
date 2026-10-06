package com.jarvis.assistant.holo

import com.jarvis.assistant.data.model.SettingKeys
import com.jarvis.assistant.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Owns the holographic workspace: which windows are open, where they are, and what is pointed at. */
class HologramController(
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
) : HoloInteraction.Callbacks {

    data class State(
        val visible: Boolean = false,
        val windows: List<HoloWindow> = emptyList(),
        val focused: HoloPanel? = null,
        val hover: HoloPanel? = null,
        /** Changes every time a deck is opened so the materialise animation restarts. */
        val bootToken: Long = 0,
    ) {
        val openPanels: List<HoloPanel> get() = windows.map { it.panel }
        /** The window the user is pointing at (hover wins over a previous click). */
        val target: HoloPanel? get() = hover ?: focused
    }

    /** Last pointer position (touch or air cursor), used for "move it here". Kept out of [State] so moving it never recomposes the UI. */
    @Volatile
    var pointer: Pair<Float, Float>? = null
        private set

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    // ------------------------------------------------------------------ show / hide

    /** Opens the workspace with the user's chosen panels (or the default set). */
    fun show(panels: List<HoloPanel>? = null) {
        _state.update { it.copy(visible = true) }
        if (_state.value.windows.isEmpty() || panels != null) {
            scope.launch {
                val chosen = panels ?: configuredPanels()
                openDeck(chosen)
            }
        }
    }

    fun hide() {
        _state.update { it.copy(visible = false, hover = null) }
    }

    private suspend fun configuredPanels(): List<HoloPanel> {
        val s = settings.current()
        return s.holoPanels.ifEmpty { HoloLayout.defaultPanels(s.holoCount) }
    }

    fun openDeck(panels: List<HoloPanel>) {
        val unique = panels.distinct().take(HoloPanel.MAX_OPEN)
        _state.update {
            it.copy(
                windows = HoloLayout.arrange(unique),
                focused = null, hover = null,
                bootToken = System.nanoTime(),
            )
        }
    }

    // ------------------------------------------------------------------ windows

    fun open(panel: HoloPanel) {
        _state.update { st ->
            if (st.windows.any { it.panel == panel }) return@update st.copy(focused = panel)
            if (st.windows.size >= HoloPanel.MAX_OPEN) return@update st
            val all = st.openPanels + panel
            val arranged = HoloLayout.arrange(all)
            val slot = arranged.last()
            val free = st.windows.none { w -> rect(w).overlaps(rect(slot)) }
            val top = (st.windows.maxOfOrNull { it.z } ?: 0) + 1
            val windows = if (free) st.windows + slot.copy(z = top) else arranged
            st.copy(windows = windows, focused = panel, visible = true)
        }
    }

    override fun close(panel: HoloPanel) {
        _state.update { st ->
            st.copy(
                windows = st.windows.filterNot { it.panel == panel },
                focused = st.focused.takeIf { it != panel },
                hover = st.hover.takeIf { it != panel },
            )
        }
    }

    fun closeAll() {
        _state.update { it.copy(windows = emptyList(), focused = null, hover = null) }
    }

    fun arrange() {
        _state.update { st ->
            st.copy(windows = HoloLayout.arrange(st.openPanels), bootToken = System.nanoTime())
        }
    }

    override fun focus(panel: HoloPanel?) {
        _state.update { it.copy(focused = panel) }
    }

    override fun hover(panel: HoloPanel?, x: Float, y: Float) {
        pointer = x to y
        _state.update { if (it.hover == panel) it else it.copy(hover = panel) }
    }

    override fun bringToFront(panel: HoloPanel) {
        _state.update { st ->
            val top = (st.windows.maxOfOrNull { it.z } ?: 0) + 1
            st.copy(windows = st.windows.map { if (it.panel == panel) it.copy(z = top) else it })
        }
    }

    override fun move(panel: HoloPanel, x: Float, y: Float) {
        _state.update { st -> st.copy(windows = st.windows.map { if (it.panel == panel && !it.maximized) it.copy(x = x, y = y) else it }) }
    }

    /** Moves the window to where the user is pointing ("move this here"). */
    fun moveToPointer(panel: HoloPanel): Boolean {
        val p = pointer ?: return false
        _state.update { st ->
            st.copy(
                windows = st.windows.map {
                    if (it.panel != panel || it.maximized) it
                    else it.copy(x = (p.first - it.w / 2).coerceIn(0f, 1f - it.w), y = (p.second - it.h / 2).coerceIn(0f, 1f - it.h))
                },
            )
        }
        return true
    }

    override fun toggleMaximize(panel: HoloPanel) {
        _state.update { st ->
            val top = (st.windows.maxOfOrNull { it.z } ?: 0) + 1
            st.copy(
                windows = st.windows.map { w ->
                    when {
                        w.panel != panel -> w
                        w.maximized -> {
                            val r = w.restore
                            if (r != null) w.copy(x = r[0], y = r[1], w = r[2], h = r[3], maximized = false, restore = null, z = top) else w
                        }
                        else -> {
                            val m = HoloLayout.maximizedRect()
                            w.copy(x = m[0], y = m[1], w = m[2], h = m[3], maximized = true, restore = listOf(w.x, w.y, w.w, w.h), z = top + 10)
                        }
                    }
                },
                focused = panel,
            )
        }
    }

    fun maximize(panel: HoloPanel) {
        if (_state.value.windows.firstOrNull { it.panel == panel }?.maximized == false) toggleMaximize(panel)
    }

    fun restoreAll() {
        _state.value.windows.filter { it.maximized }.forEach { toggleMaximize(it.panel) }
    }

    // Callbacks the UI wires to the hub / actions.
    var onRefresh: (HoloPanel) -> Unit = {}
    var onAction: (HoloPanel, String) -> Unit = { _, _ -> }

    override fun refresh(panel: HoloPanel) = onRefresh(panel)
    override fun action(panel: HoloPanel, actionId: String) = onAction(panel, actionId)

    /** Remember the chosen set so the next "open hologram" restores it. */
    fun persistChoice() {
        val csv = _state.value.openPanels.joinToString(",") { it.name }
        scope.launch { settings.put(SettingKeys.HOLO_PANELS, csv) }
    }

    private fun rect(w: HoloWindow) = FRect(w.x, w.y, w.x + w.w, w.y + w.h)
}

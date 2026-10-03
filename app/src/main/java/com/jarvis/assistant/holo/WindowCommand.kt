package com.jarvis.assistant.holo

import com.jarvis.assistant.ai.AiAction
import com.jarvis.assistant.command.ActionResult
import com.jarvis.assistant.command.Command

/** Voice control of the holographic windows ("날씨 창 열어줘", "이거 닫아줘", "여기로 옮겨줘"). */
class WindowCommand(
    private val holo: HologramController,
    private val data: HoloDataHub,
) : Command {
    override val types = listOf(
        "HOLOGRAM_OPEN", "HOLOGRAM_CLOSE", "WINDOW_OPEN", "WINDOW_CLOSE", "WINDOW_CLOSE_ALL", "WINDOW_ARRANGE",
        "WINDOW_MAXIMIZE", "WINDOW_RESTORE", "WINDOW_MOVE_HERE", "WINDOW_REFRESH",
    )
    override val executesBeforeSpeech = true

    private fun target(action: AiAction): HoloPanel? =
        action.param("panel")?.let { HoloPanel.fromSpoken(it) } ?: holo.state.value.target

    override suspend fun execute(action: AiAction): ActionResult {
        val kind = action.type.trim().uppercase().replace(Regex("[^A-Z0-9]+"), "_")
        return when (kind) {
            "HOLOGRAM_OPEN" -> {
                val count = action.param("count")?.toDoubleOrNull()?.toInt()
                holo.show(count?.let { HoloLayout.defaultPanels(it) })
                ActionResult.ok("Projecting the holographic display.", "홀로그램 디스플레이를 띄웁니다.")
            }
            "HOLOGRAM_CLOSE" -> {
                holo.hide()
                ActionResult.ok("Display closed.", "홀로그램을 닫았습니다.")
            }
            "WINDOW_OPEN" -> {
                val panel = action.param("panel")?.let { HoloPanel.fromSpoken(it) }
                    ?: return ActionResult.fail("Which window should I open?", "어떤 창을 열까요?")
                holo.show()
                holo.open(panel)
                data.ensureLoaded(listOf(panel))
                ActionResult.ok("Opening the ${panel.title.lowercase()} window.", "${panel.ko} 창을 엽니다.")
            }
            "WINDOW_CLOSE_ALL" -> {
                holo.closeAll()
                ActionResult.ok("All windows closed.", "모든 창을 닫았습니다.")
            }
            "WINDOW_ARRANGE" -> {
                holo.arrange()
                ActionResult.ok("Rearranged.", "창을 정리했습니다.")
            }
            "WINDOW_CLOSE" -> {
                val p = target(action) ?: return ActionResult.fail("Point at a window first, or name it.", "닫을 창을 가리키거나 이름을 말씀해 주세요.")
                holo.close(p)
                ActionResult.ok("Closed.", "${p.ko} 창을 닫았습니다.")
            }
            "WINDOW_MAXIMIZE" -> {
                val p = target(action) ?: return ActionResult.fail("Which window?", "어떤 창을 키울까요?")
                holo.maximize(p)
                ActionResult.ok("Enlarged.", "${p.ko} 창을 키웠습니다.")
            }
            "WINDOW_RESTORE" -> {
                holo.restoreAll()
                ActionResult.ok("Restored.", "창 크기를 되돌렸습니다.")
            }
            "WINDOW_MOVE_HERE" -> {
                val p = target(action) ?: return ActionResult.fail("Which window should I move?", "어떤 창을 옮길까요?")
                if (holo.moveToPointer(p)) ActionResult.ok("Moved.", "${p.ko} 창을 옮겼습니다.")
                else ActionResult.fail("Point to where it should go.", "옮길 위치를 가리켜 주세요.")
            }
            else -> {
                val p = target(action) ?: return ActionResult.fail("Which window?", "어떤 창을 새로고칠까요?")
                data.refresh(p)
                ActionResult.ok("Refreshing.", "${p.ko} 창을 새로고침합니다.")
            }
        }
    }
}

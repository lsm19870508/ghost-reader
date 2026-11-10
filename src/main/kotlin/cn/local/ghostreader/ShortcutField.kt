package cn.local.ghostreader

import java.awt.event.InputEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.JTextField
import javax.swing.KeyStroke

/** Captures a chord rather than requiring users to type Swing key-stroke syntax. */
class ShortcutField : JTextField(24) {
    var strokeText: String = ""
        set(value) {
            field = value
            val stroke = KeyStroke.getKeyStroke(value)
            text = if (stroke == null) "未设置" else listOf(
                InputEvent.getModifiersExText(stroke.modifiers), KeyEvent.getKeyText(stroke.keyCode)
            ).filter { it.isNotBlank() }.joinToString(" + ")
        }

    init {
        isEditable = false
        toolTipText = "点击后按组合键；单独按 Backspace 清除。组合键需要 Ctrl、Alt、Meta，或使用功能键。"
        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(event: KeyEvent) {
                if (event.keyCode == KeyEvent.VK_TAB) return
                event.consume()
                if (event.keyCode == KeyEvent.VK_BACK_SPACE && event.modifiersEx == 0) { strokeText = ""; return }
                val modifiers = InputEvent.CTRL_DOWN_MASK or InputEvent.ALT_DOWN_MASK or InputEvent.META_DOWN_MASK
                val functionKey = event.keyCode in KeyEvent.VK_F1..KeyEvent.VK_F12 || event.keyCode in KeyEvent.VK_F13..KeyEvent.VK_F24
                if (event.modifiersEx and modifiers == 0 && !functionKey) return
                if (event.keyCode in listOf(KeyEvent.VK_CONTROL, KeyEvent.VK_SHIFT, KeyEvent.VK_ALT, KeyEvent.VK_META, KeyEvent.VK_ALT_GRAPH)) return
                strokeText = KeyStroke.getKeyStroke(event.keyCode, event.modifiersEx).toString()
            }
        })
    }
}

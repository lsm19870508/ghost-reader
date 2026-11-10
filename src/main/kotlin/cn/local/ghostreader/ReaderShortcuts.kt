package cn.local.ghostreader

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import java.util.IdentityHashMap
import javax.swing.KeyStroke

@Service(Service.Level.APP)
class ReaderShortcuts : Disposable {
    private val installed = IdentityHashMap<Editor, List<AnAction>>()

    init {
        EditorFactory.getInstance().addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) { install(event.editor) }
            override fun editorReleased(event: EditorFactoryEvent) { uninstall(event.editor) }
        }, this)
        refresh()
    }

    fun refresh() {
        definitions.forEach { definition ->
            val stroke = KeyStroke.getKeyStroke(value(definition.id))
            ActionManager.getInstance().getAction("GhostReader.${definition.id}")?.shortcutSet =
                if (stroke == null) CustomShortcutSet.EMPTY else CustomShortcutSet(stroke)
        }
        EditorFactory.getInstance().allEditors.forEach(::install)
    }

    private fun install(editor: Editor) {
        uninstall(editor)
        if (editor.isDisposed || editor.isOneLineMode || editor.editorKind != EditorKind.MAIN_EDITOR) return
        installed[editor] = definitions.mapNotNull { definition ->
            val stroke = KeyStroke.getKeyStroke(value(definition.id)) ?: return@mapNotNull null
            ActionManager.getInstance().getAction("GhostReader.${definition.id}")?.also {
                it.registerCustomShortcutSet(CustomShortcutSet(stroke), editor.contentComponent)
            }
        }
    }

    private fun uninstall(editor: Editor) {
        installed.remove(editor)?.forEach { it.unregisterCustomShortcutSet(editor.contentComponent) }
    }

    override fun dispose() { installed.keys.toList().forEach(::uninstall) }

    companion object {
        data class Definition(val id: String, val label: String, val default: String)
        val definitions = listOf(
            Definition("NextPage", "下一页", "ctrl RIGHT"),
            Definition("PreviousPage", "上一页", "ctrl LEFT"),
            Definition("NextChapter", "下一章", "ctrl shift RIGHT"),
            Definition("PreviousChapter", "上一章开头", "ctrl shift LEFT"),
            Definition("Toggle", "隐藏 / 显示", "ctrl alt shift H"),
            Definition("AnchorHere", "跟随光标", "ctrl alt shift A"),
            Definition("Configure", "打开设置", "ctrl alt shift O")
        )
        fun value(id: String): String = service<ReaderSettings>().state.shortcuts[id]
            ?: definitions.first { it.id == id }.default
        fun usesDefault(id: String): Boolean = KeyStroke.getKeyStroke(value(id)) ==
            KeyStroke.getKeyStroke(definitions.first { it.id == id }.default)
    }
}

package cn.local.ghostreader

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionHandler

/** Contextual wrappers avoid shortcut conflicts without rewriting the user's keymap. */
abstract class ReaderNavigationHandler(
    private val originalHandler: EditorActionHandler,
    private val forward: Boolean,
    private val chapter: Boolean
) : EditorActionHandler() {
    override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext) {
        val settings = service<ReaderSettings>().state
        val project = editor.project
        val command = if (chapter) { if (forward) "NextChapter" else "PreviousChapter" }
            else if (forward) "NextPage" else "PreviousPage"
        if (ReaderShortcuts.usesDefault(command) && settings.visible && settings.novelPath.isNotBlank() && project != null && !project.isDisposed &&
            !editor.isOneLineMode && project.service<ReaderController>().consumeNavigation(editor, forward, chapter)) return
        originalHandler.execute(editor, caret, dataContext)
    }

    override fun isEnabledForCaret(editor: Editor, caret: Caret, dataContext: DataContext): Boolean =
        originalHandler.isEnabled(editor, caret, dataContext)
}

class ReaderNextWordHandler(original: EditorActionHandler) : ReaderNavigationHandler(original, true, false)
class ReaderPreviousWordHandler(original: EditorActionHandler) : ReaderNavigationHandler(original, false, false)
class ReaderNextWordSelectionHandler(original: EditorActionHandler) : ReaderNavigationHandler(original, true, true)
class ReaderPreviousWordSelectionHandler(original: EditorActionHandler) : ReaderNavigationHandler(original, false, true)

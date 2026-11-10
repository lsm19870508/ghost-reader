package cn.local.ghostreader

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAwareAction

abstract class ReaderAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) { e.presentation.isEnabled = e.project != null }
}

abstract class ReaderNavigationAction : ReaderAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) {
        val settings = service<ReaderSettings>().state
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabled = project != null && settings.visible && settings.novelPath.isNotBlank() &&
            editor != null && !editor.isOneLineMode && editor === FileEditorManager.getInstance(project).selectedTextEditor
    }
}

class NextPageAction : ReaderNavigationAction() {
    override fun actionPerformed(e: AnActionEvent) { e.project?.service<ReaderController>()?.turn(true) }
}
class PreviousPageAction : ReaderNavigationAction() {
    override fun actionPerformed(e: AnActionEvent) { e.project?.service<ReaderController>()?.turn(false) }
}
class NextChapterAction : ReaderNavigationAction() {
    override fun actionPerformed(e: AnActionEvent) { e.project?.service<ReaderController>()?.turnChapter(true) }
}
class PreviousChapterAction : ReaderNavigationAction() {
    override fun actionPerformed(e: AnActionEvent) { e.project?.service<ReaderController>()?.turnChapter(false) }
}
class ToggleReaderAction : ReaderAction() {
    override fun actionPerformed(e: AnActionEvent) { e.project?.service<ReaderController>()?.toggle() }
}
class AnchorHereAction : ReaderAction() {
    override fun actionPerformed(e: AnActionEvent) { e.project?.service<ReaderController>()?.anchorHere() }
}
class RestartBookAction : ReaderAction() {
    override fun actionPerformed(e: AnActionEvent) { e.project?.service<ReaderController>()?.restart() }
}
class ReloadBookAction : ReaderAction() {
    override fun actionPerformed(e: AnActionEvent) { ReaderController.reloadAll() }
}
class ConfigureReaderAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) { openSettings(e.project) }
}

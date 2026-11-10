package cn.local.ghostreader

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.startup.ProjectActivity
import java.nio.charset.CharacterCodingException
import java.nio.file.Path

@Service(Service.Level.PROJECT)
class ReaderController(private val project: Project) : Disposable {
    private var book: BookLoader.Book? = null
    private var inlay: Inlay<CommentRenderer>? = null
    private var generation = 0
    private var loading = false
    private var rendering = false
    private var renderScheduled = false
    private val settings get() = service<ReaderSettings>().state

    init {
        project.messageBus.connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) { render() }
        })
        EditorFactory.getInstance().eventMulticaster.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
                if (!settings.followCaret || !settings.visible || event.editor.project != project ||
                    event.caret != event.editor.caretModel.primaryCaret || rendering || renderScheduled) return
                if (event.editor !== FileEditorManager.getInstance(project).selectedTextEditor) return
                renderScheduled = true
                ApplicationManager.getApplication().invokeLater {
                    renderScheduled = false
                    if (!project.isDisposed && settings.followCaret) render()
                }
            }
        }, this)
    }

    fun reload() {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val ticket = ++generation
        removeInlay()
        book = null
        loading = false
        if (settings.novelPath.isBlank() || !settings.visible) return
        val path = settings.novelPath
        val encoding = settings.encoding
        val columns = settings.columns
        val pageLines = settings.pageLines
        loading = true
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "正在读取本地 TXT", true) {
            private var result: BookLoader.Book? = null
            private var error: Exception? = null
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.checkCanceled()
                    result = BookLoader.load(path, encoding, columns, pageLines)
                    indicator.checkCanceled()
                } catch (cancelled: com.intellij.openapi.progress.ProcessCanceledException) {
                    throw cancelled
                } catch (exception: Exception) {
                    error = exception
                }
            }
            override fun onSuccess() {
                if (project.isDisposed || ticket != generation) return
                loading = false
                book = result
                result?.let { loaded ->
                    if (settings.pendingStartPath == path && settings.pendingStartOffset >= 0) {
                        settings.offsets[loaded.key] = loaded.engine.pageAt(settings.pendingStartOffset).offset
                        settings.pendingStartPath = ""
                        settings.pendingStartOffset = -1
                    }
                }
                if (error != null) {
                    val message = if (error is CharacterCodingException) "文字编码不匹配，请在设置中改为 GB18030、UTF-8 或 UTF-16。"
                        else error?.message ?: "无法读取文件，请检查路径及权限。"
                    notify(message, NotificationType.ERROR)
                } else render()
            }
            override fun onCancel() { if (!project.isDisposed && ticket == generation) loading = false }
        })
    }

    fun turn(forward: Boolean) {
        if (!settings.visible) return
        val current = book
        if (current == null) { if (!loading) reload(); return }
        val page = current.engine.pageAt(settings.offsets[current.key] ?: 0)
        settings.offsets[current.key] = if (forward) page.next else page.previous
        renderAll()
    }

    fun turnChapter(forward: Boolean) {
        if (!settings.visible) return
        val current = book
        if (current == null) { if (!loading) reload(); return }
        settings.offsets[current.key] = current.engine.chapterOffset(settings.offsets[current.key] ?: 0, forward)
        renderAll()
    }

    /** Intercept word-navigation only in the active reading editor; all other contexts delegate. */
    fun consumeNavigation(editor: Editor, forward: Boolean, chapter: Boolean): Boolean {
        if (!settings.visible || settings.novelPath.isBlank() || editor.isOneLineMode ||
            editor !== FileEditorManager.getInstance(project).selectedTextEditor) return false
        if (chapter) turnChapter(forward) else turn(forward)
        return true
    }

    fun toggle() {
        if (settings.novelPath.isBlank()) { openSettings(project); return }
        settings.visible = !settings.visible
        ProjectManager.getInstance().openProjects.forEach {
            if (!it.isDisposed) it.service<ReaderController>().applyVisibility()
        }
    }

    private fun applyVisibility() {
        if (settings.visible && book == null && !loading) reload() else render()
    }

    fun anchorHere() {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
        settings.anchorLine = editor.caretModel.logicalPosition.line + 1
        settings.indentColumns = editor.caretModel.logicalPosition.column.coerceIn(0, 160)
        settings.followCaret = true
        renderAll()
    }

    fun restart() {
        val loaded = book
        if (loaded != null) {
            settings.offsets[loaded.key] = 0
            renderAll()
            return
        }
        val path = settings.novelPath
        if (path.isBlank()) return
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "重置当前小说进度", false) {
            private var key: String? = null
            private var error: Exception? = null
            override fun run(indicator: ProgressIndicator) {
                try { key = Path.of(path).toRealPath().toString() }
                catch (exception: Exception) { error = exception }
            }
            override fun onSuccess() {
                if (project.isDisposed) return
                key?.let { settings.offsets[it] = 0 }
                if (error != null) notify("无法定位小说文件，请检查路径及权限。", NotificationType.ERROR)
                renderAll()
            }
        })
    }

    fun render() {
        if (project.isDisposed || rendering) return
        ApplicationManager.getApplication().assertIsDispatchThread()
        rendering = true
        try {
            removeInlay()
            if (!settings.visible) return
            val current = book ?: return
            val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
            if (editor.isDisposed || editor.isOneLineMode) return
            val document = editor.document
            val position = editor.caretModel.logicalPosition
            val line = (if (settings.followCaret) position.line else settings.anchorLine - 1).coerceIn(0, document.lineCount - 1)
            val indent = if (settings.followCaret) position.column.coerceIn(0, 160) else settings.indentColumns
            val page = current.engine.pageAt(settings.offsets[current.key] ?: 0)
            settings.offsets[current.key] = page.offset
            inlay = editor.inlayModel.addBlockElement(document.getLineEndOffset(line), true, false, 0,
                CommentRenderer(page.lines, settings.prefix, indent))
        } finally { rendering = false }
    }

    private fun removeInlay() { inlay?.dispose(); inlay = null }
    private fun notify(message: String, type: NotificationType) = NotificationGroupManager.getInstance()
        .getNotificationGroup("GhostReader").createNotification("小说隐形阅读器", message, type).notify(project)

    override fun dispose() { generation++; removeInlay(); book = null }

    companion object {
        fun reloadAll() = ProjectManager.getInstance().openProjects.forEach { if (!it.isDisposed) it.service<ReaderController>().reload() }
        fun renderAll() = ProjectManager.getInstance().openProjects.forEach { if (!it.isDisposed) it.service<ReaderController>().render() }
    }
}

class ReaderStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                service<ReaderShortcuts>().refresh()
                project.service<ReaderController>().reload()
            }
        }
    }
}

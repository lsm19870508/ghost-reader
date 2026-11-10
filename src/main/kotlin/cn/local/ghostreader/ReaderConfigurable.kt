package cn.local.ghostreader

import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.util.ui.JBUI
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.JTextField
import javax.swing.SpinnerNumberModel
import javax.swing.DefaultComboBoxModel
import javax.swing.JScrollPane
import javax.swing.KeyStroke
import java.awt.Dimension

fun openSettings(project: Project?) = ShowSettingsUtil.getInstance().showSettingsDialog(project, ReaderConfigurable::class.java)

class ReaderConfigurable : SearchableConfigurable {
    private val path = JTextField(38).apply { name = "novelPath" }
    private val encoding = JComboBox(arrayOf("AUTO", "UTF-8", "GB18030", "UTF-16LE", "UTF-16BE"))
    private val columns = JSpinner(SpinnerNumberModel(72, 20, 160, 2))
    private val pageLines = JSpinner(SpinnerNumberModel(4, 1, 20, 1)).apply { name = "pageLines" }
    private data class ChapterChoice(val label: String, val offset: Int = -1) { override fun toString() = label }
    private val chapters = JComboBox(arrayOf(ChapterChoice("继续上次进度"))).apply { name = "chapterChooser" }
    private val chapterStatus = JLabel("选择 TXT 后点击“读取章节目录”，可指定从哪一章开始。")
    private val shortcutFields = ReaderShortcuts.definitions.associate { it.id to ShortcutField() }
    private var chapterPath = ""
    private var chapterEncoding = ""
    private var chapterTicket = 0
    private val line = JSpinner(SpinnerNumberModel(1, 1, 1_000_000, 1))
    private val indent = JSpinner(SpinnerNumberModel(0, 0, 160, 1))
    private val prefix = JTextField("# ", 12)
    private val visible = JCheckBox("显示阅读内容（重启 IDE 后恢复）")
    private val followCaret = JCheckBox("跟随当前光标行显示（推荐）", true).apply {
        addActionListener { updatePositionFields() }
    }
    private val settings get() = service<ReaderSettings>().state

    override fun getId() = "cn.local.ghostreader.settings"
    override fun getDisplayName() = "小说隐形阅读器 / Ghost Reader"

    override fun createComponent(): JComponent {
        val panel = JPanel(GridBagLayout())
        var row = 0
        fun addRow(label: String, component: JComponent) {
            panel.add(JLabel(label), GridBagConstraints().apply {
                gridx = 0; gridy = row; anchor = GridBagConstraints.WEST; insets = JBUI.insets(5, 0, 5, 12)
            })
            panel.add(component, GridBagConstraints().apply {
                gridx = 1; gridy = row++; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; insets = JBUI.insets(5, 0)
            })
        }
        val filePanel = JPanel(BorderLayout(JBUI.scale(6), 0))
        filePanel.add(path, BorderLayout.CENTER)
        filePanel.add(JButton("浏览…").apply {
            addActionListener {
                FileChooser.chooseFile(FileChooserDescriptorFactory.createSingleFileDescriptor("txt"), null, null) {
                    path.text = it.path
                    loadChapters()
                }
            }
        }, BorderLayout.EAST)
        addRow("本地 TXT 路径", filePanel)
        addRow("文字编码", encoding)
        addRow("每行宽度（中文约占 2 格）", columns)
        addRow("每页显示行数（1–20）", pageLines)
        val chapterPanel = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            add(chapters, BorderLayout.CENTER)
            add(JButton("读取章节目录").apply { addActionListener { loadChapters() } }, BorderLayout.EAST)
        }
        addRow("从哪一章开始", chapterPanel)
        addRow("", chapterStatus)
        addRow("显示位置", followCaret)
        addRow("显示在第几行之后", line)
        addRow("左侧缩进（空格数）", indent)
        addRow("虚拟注释前缀", prefix)
        addRow("", visible)
        addRow("快捷键", JLabel("点击下面的输入框，直接按所需组合键；Backspace 清除。"))
        ReaderShortcuts.definitions.forEach { addRow(it.label, shortcutFields.getValue(it.id)) }
        addRow("", JButton("恢复默认快捷键").apply { addActionListener {
            ReaderShortcuts.definitions.forEach { shortcutFields.getValue(it.id).strokeText = it.default }
        } })
        addRow("", JLabel("<html>编码 AUTO 支持 UTF-8 与带 BOM 的 UTF-16；GBK 请选择 GB18030。<br>自动跳过纯空白行，中文字体自动回退。跟随模式在光标行下方显示。<br>选择某一章后点击 Apply / OK，将从该章开始；后续正常保存阅读进度。<br>翻页/章节快捷键只在小说显示时生效；隐藏后恢复编辑器移动/选中。<br>内容只绘制在编辑器中，不写入代码、复制内容或 Git diff。</html>"))
        panel.add(JPanel(), GridBagConstraints().apply { gridx = 0; gridy = row; weighty = 1.0 })
        return JScrollPane(panel).apply { border = JBUI.Borders.empty(); preferredSize = Dimension(760, 650) }
    }

    override fun reset() {
        path.text = settings.novelPath
        encoding.selectedItem = settings.encoding
        columns.value = settings.columns.coerceIn(20, 160)
        pageLines.value = settings.pageLines.coerceIn(1, 20)
        ReaderShortcuts.definitions.forEach { shortcutFields.getValue(it.id).strokeText = ReaderShortcuts.value(it.id) }
        chapters.selectedIndex = 0
        line.value = settings.anchorLine.coerceIn(1, 1_000_000)
        indent.value = settings.indentColumns.coerceIn(0, 160)
        prefix.text = settings.prefix
        visible.isSelected = settings.visible
        followCaret.isSelected = settings.followCaret
        updatePositionFields()
    }

    override fun isModified(): Boolean = path.text.trim() != settings.novelPath ||
        encoding.selectedItem != settings.encoding || columns.value != settings.columns ||
        line.value != settings.anchorLine || indent.value != settings.indentColumns ||
        prefix.text != settings.prefix || visible.isSelected != settings.visible || followCaret.isSelected != settings.followCaret ||
        pageLines.value != settings.pageLines || chapters.selectedIndex > 0 ||
        ReaderShortcuts.definitions.any { KeyStroke.getKeyStroke(shortcutFields.getValue(it.id).strokeText) != KeyStroke.getKeyStroke(ReaderShortcuts.value(it.id)) }

    override fun apply() {
        val file = path.text.trim()
        if (file.isNotEmpty()) {
            try {
                if (!Path.of(file).isAbsolute) throw ConfigurationException("请输入 TXT 文件的绝对路径。")
            } catch (invalid: java.nio.file.InvalidPathException) {
                throw ConfigurationException("文件路径格式无效。")
            }
        }
        if (prefix.text.length > 16 || prefix.text.any { Character.isISOControl(it) }) {
            throw ConfigurationException("注释前缀最多 16 个字符，不能包含换行或控制字符。")
        }
        val chapter = chapters.selectedItem as? ChapterChoice
        if (chapter != null && chapter.offset >= 0 && (file != chapterPath || encoding.selectedItem != chapterEncoding)) {
            throw ConfigurationException("路径或编码已变化，请重新读取章节目录。")
        }
        val used = hashSetOf<KeyStroke>()
        for ((_, field) in shortcutFields) {
            val stroke = KeyStroke.getKeyStroke(field.strokeText) ?: continue
            if (!used.add(stroke)) throw ConfigurationException("快捷键重复，请为每个操作选择不同的组合键。")
        }
        if (settings.novelPath != file || settings.encoding != encoding.selectedItem) {
            settings.pendingStartPath = ""
            settings.pendingStartOffset = -1
        }
        settings.novelPath = file
        settings.encoding = encoding.selectedItem as String
        settings.columns = columns.value as Int
        settings.pageLines = pageLines.value as Int
        settings.shortcuts = shortcutFields.mapValues { it.value.strokeText }.toMutableMap()
        if (chapter != null && chapter.offset >= 0) {
            settings.pendingStartPath = file
            settings.pendingStartOffset = chapter.offset
        }
        settings.anchorLine = line.value as Int
        settings.followCaret = followCaret.isSelected
        settings.indentColumns = indent.value as Int
        settings.prefix = prefix.text
        settings.visible = visible.isSelected && file.isNotEmpty()
        service<ReaderShortcuts>().refresh()
        ReaderController.reloadAll()
        chapters.selectedIndex = 0
    }

    private fun updatePositionFields() {
        line.isEnabled = !followCaret.isSelected
        indent.isEnabled = !followCaret.isSelected
    }

    private fun loadChapters() {
        val file = path.text.trim()
        if (file.isBlank()) { chapterStatus.text = "请先选择 TXT 文件。"; return }
        val ticket = ++chapterTicket
        val selectedEncoding = encoding.selectedItem as String
        val width = columns.value as Int
        val rows = pageLines.value as Int
        chapters.model = DefaultComboBoxModel(arrayOf(ChapterChoice("继续上次进度")))
        chapterStatus.text = "正在读取章节目录…"
        ProgressManager.getInstance().run(object : Task.Backgroundable(null, "读取小说章节目录", true) {
            private var result: List<PageEngine.Chapter> = emptyList()
            private var error: Exception? = null
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.checkCanceled()
                    result = BookLoader.load(file, selectedEncoding, width, rows).engine.chapters
                    indicator.checkCanceled()
                } catch (cancelled: com.intellij.openapi.progress.ProcessCanceledException) { throw cancelled }
                catch (exception: Exception) { error = exception }
            }
            override fun onSuccess() {
                if (ticket != chapterTicket) return
                if (error != null) {
                    chapterStatus.text = "读取失败，请检查路径及文字编码。"
                    return
                }
                chapterPath = file
                chapterEncoding = selectedEncoding
                chapters.model = DefaultComboBoxModel((listOf(ChapterChoice("继续上次进度")) +
                    result.mapIndexed { index, chapter -> ChapterChoice("${index + 1}. ${chapter.title}", chapter.offset) }).toTypedArray())
                chapterStatus.text = if (result.isEmpty()) "未识别到常见章节标题，将继续上次进度。" else "已识别 ${result.size} 章；可在目录中选择起始章节。"
            }
            override fun onCancel() { if (ticket == chapterTicket) chapterStatus.text = "已取消读取章节目录。" }
        })
    }

    override fun disposeUIResources() { chapterTicket++ }
}

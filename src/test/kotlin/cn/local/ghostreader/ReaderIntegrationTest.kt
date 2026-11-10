package cn.local.ghostreader

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.colors.impl.EditorColorsSchemeImpl
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer
import java.nio.file.Files
import java.nio.file.Path
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import javax.swing.KeyStroke
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.options.ConfigurationException
import java.awt.Component
import java.awt.Container
import java.awt.Color
import java.awt.Font
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JSpinner
import javax.swing.JTextField

class ReaderIntegrationTest : BasePlatformTestCase() {
    private lateinit var previousState: ReaderSettings.Data
    private var novel: Path? = null

    override fun setUp() {
        super.setUp()
        val settings = service<ReaderSettings>()
        previousState = XmlSerializer.deserialize(XmlSerializer.serialize(settings.state), ReaderSettings.Data::class.java)
        settings.loadState(ReaderSettings.Data())
        service<ReaderShortcuts>().refresh()
    }

    override fun tearDown() {
        try {
            service<ReaderSettings>().loadState(previousState)
            service<ReaderShortcuts>().refresh()
            project.service<ReaderController>().render()
            novel?.let(Files::deleteIfExists)
        } finally { super.tearDown() }
    }

    fun testVirtualCommentLifecycleAndNavigationNeverChangeDocument() {
        val source = "def greet(name):\n    return 'hello ' + name\n\nprint(greet('reader'))\n"
        myFixture.configureByText("demo.py", source)
        val document = myFixture.editor.document
        val stamp = document.modificationStamp
        novel = Files.createTempFile("ghost-reader-test-", ".txt")
        val text = (1..13).joinToString("\n") { "第${it}行，测试小说内容。" }
        Files.writeString(novel!!, text)
        val settings = service<ReaderSettings>().state
        settings.novelPath = novel.toString()
        settings.visible = true
        val controller = project.service<ReaderController>()
        controller.reload()
        PlatformTestUtil.waitWithEventsDispatching("Reader did not create its block inlay", { readerInlays().size == 1 }, 15)

        val first = readerInlays().single()
        assertEquals(myFixture.editor.lineHeight * 4, first.heightInPixels)
        assertEquals(document.getLineEndOffset(0), first.offset)
        assertEquals(0, settings.offsets.values.single())

        controller.turn(true)
        assertTrue(settings.offsets.values.single() > 0)
        assertEquals(1, readerInlays().size)
        controller.turn(false)
        assertEquals(0, settings.offsets.values.single())

        myFixture.editor.caretModel.moveToOffset(document.getLineStartOffset(1) + 4)
        controller.anchorHere()
        assertEquals(2, settings.anchorLine)
        assertEquals(4, settings.indentColumns)
        assertEquals(document.getLineEndOffset(1), readerInlays().single().offset)

        controller.toggle()
        assertFalse(settings.visible)
        assertTrue(readerInlays().isEmpty())
        controller.turn(true)
        assertEquals(0, settings.offsets.values.single())
        controller.toggle()
        assertTrue(settings.visible)
        assertEquals(1, readerInlays().size)

        assertEquals(source, document.text)
        assertEquals(stamp, document.modificationStamp)
        assertEquals(text, Files.readString(novel!!))
    }

    fun testSettingsRoundTripKeepsEachBookAndAllShortcutsAreRegistered() {
        val original = service<ReaderSettings>().state
        original.novelPath = "C:/books/测试.txt"
        original.encoding = "GB18030"
        original.visible = true
        original.anchorLine = 23
        original.indentColumns = 8
        original.followCaret = false
        original.pageLines = 6
        original.shortcuts["NextPage"] = "alt DOWN"
        original.offsets["book-a.txt"] = 144
        original.offsets["book-b.txt"] = 288
        val restored = ReaderSettings()
        restored.loadState(XmlSerializer.deserialize(XmlSerializer.serialize(original), ReaderSettings.Data::class.java))
        assertEquals(original.novelPath, restored.state.novelPath)
        assertEquals(original.encoding, restored.state.encoding)
        assertEquals(original.visible, restored.state.visible)
        assertEquals(original.anchorLine, restored.state.anchorLine)
        assertEquals(original.indentColumns, restored.state.indentColumns)
        assertEquals(original.followCaret, restored.state.followCaret)
        assertEquals(original.pageLines, restored.state.pageLines)
        assertEquals(original.shortcuts, restored.state.shortcuts)
        assertEquals(original.offsets, restored.state.offsets)
        service<ReaderShortcuts>().refresh()
        for (id in listOf("NextPage", "PreviousPage", "NextChapter", "PreviousChapter", "Toggle", "AnchorHere", "Configure")) {
            val action = ActionManager.getInstance().getAction("GhostReader.$id")
            assertNotNull("Missing action $id", action)
            assertTrue("Missing shortcut for $id", action.shortcutSet.shortcuts.isNotEmpty())
        }
        for ((id, stroke) in mapOf("NextPage" to "alt DOWN", "PreviousPage" to "ctrl LEFT",
            "NextChapter" to "ctrl shift RIGHT", "PreviousChapter" to "ctrl shift LEFT")) {
            assertTrue(ActionManager.getInstance().getAction("GhostReader.$id").shortcutSet.shortcuts
                .filterIsInstance<KeyboardShortcut>().any { it.firstKeyStroke == KeyStroke.getKeyStroke(stroke) })
        }
    }

    fun testChangingShortcutsRefreshesEditorAndReleasesDefaultWordAction() {
        loadNovel((1..20).joinToString("\n") { "内容 $it" })
        val settings = service<ReaderSettings>().state
        settings.shortcuts["NextPage"] = "alt DOWN"
        service<ReaderShortcuts>().refresh()
        assertFalse(ReaderShortcuts.usesDefault("NextPage"))
        val old = myFixture.editor.caretModel.offset
        myFixture.performEditorAction("EditorNextWord")
        assertTrue(myFixture.editor.caretModel.offset > old)
        assertEquals(0, settings.offsets.values.single())
        myFixture.performEditorAction("GhostReader.NextPage")
        assertTrue(settings.offsets.values.single() > 0)
        assertTrue(ActionManager.getInstance().getAction("GhostReader.NextPage").shortcutSet.shortcuts
            .filterIsInstance<KeyboardShortcut>().any { it.firstKeyStroke == KeyStroke.getKeyStroke("alt DOWN") })
        settings.shortcuts["NextPage"] = ""
        service<ReaderShortcuts>().refresh()
        assertTrue(ActionManager.getInstance().getAction("GhostReader.NextPage").shortcutSet.shortcuts.isEmpty())
    }

    fun testSettingsSelectChapterAndResizePagesWithoutResettingOtherBooks() {
        val text = "第1章 第一封信\n钟声穿过巷口，灯光落在窗前。\n书店里的人翻开了新的故事。\n风把信纸轻轻吹向桌边。\n信封上只有一个陌生的名字。\n他决定在天亮前找到答案。\n第2章 末班列车\n月光照亮空荡荡的站台。\n蓝色的列车缓缓停靠。\n车门打开，远方的风迎面而来。\n旅人把车票交给了列车员。\n下一站写着无人听过的地名。\n第3章 回到书店\n清晨的阳光重新落在书页上。\n故事还会继续。\n门口的铜铃又响了一声。\n朋友把新书放回木架上。\n新的一天从这一页开始。"
        val controller = loadNovel(text)
        captureDemo("01-reading", "默认四行 · 跟随光标 · 中文字体回退")
        controller.turn(true)
        captureDemo("02-next-page", "下一页 · 跳过空白行 · 不修改源代码")
        controller.turnChapter(true)
        captureDemo("03-chapter", "下一章 · Ctrl + Shift + →")
        val config = ReaderConfigurable()
        val ui = config.createComponent()
        config.reset()
        val components = descendants(ui)
        val rows = components.first { it.name == "pageLines" } as JSpinner
        rows.value = 6
        (components.filterIsInstance<JButton>().first { it.text == "读取章节目录" }).doClick()
        val choices = components.first { it.name == "chapterChooser" } as JComboBox<*>
        PlatformTestUtil.waitWithEventsDispatching("Chapter list did not load", { choices.itemCount == 4 }, 15)
        choices.selectedIndex = 3
        service<ReaderSettings>().state.offsets["another-book.txt"] = 100
        config.apply()
        PlatformTestUtil.waitWithEventsDispatching("Selected chapter did not apply", {
            readerInlays().singleOrNull()?.heightInPixels == myFixture.editor.lineHeight * 6 &&
                service<ReaderSettings>().state.pendingStartOffset == -1
        }, 15)
        val settings = service<ReaderSettings>().state
        assertEquals(text.indexOf("第3章"), settings.offsets[novel!!.toRealPath().toString()])
        assertEquals(100, settings.offsets["another-book.txt"])
        captureDemo("04-start-chapter", "设置：从第 3 章开始 · 每页 6 行")
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.getLineStartOffset(3))
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        captureDemo("05-follow-caret", "移动光标 · 阅读区域自动跟随")
        controller.toggle()
        captureDemo("06-hidden", "一键隐藏 · 编辑器恢复原有快捷键")
        controller.toggle()
        captureDemo("07-restored", "重新显示 · 保留阅读进度")
        assertEquals("def greet(name):\n    return 'hello ' + name\n\nprint(greet('reader'))\n", myFixture.editor.document.text)
        config.disposeUIResources()
    }

    fun testSettingsRejectDuplicateShortcuts() {
        val config = ReaderConfigurable()
        val ui = config.createComponent()
        config.reset()
        val fields = descendants(ui).filterIsInstance<ShortcutField>()
        fields[0].strokeText = "alt RIGHT"
        fields[1].strokeText = "alt RIGHT"
        try { config.apply(); fail("Duplicate shortcuts accepted") }
        catch (expected: ConfigurationException) { assertTrue(expected.message!!.contains("重复")) }
        finally { config.disposeUIResources() }
    }

    private fun descendants(component: Component): List<Component> = listOf(component) +
        if (component is Container) component.components.flatMap(::descendants) else emptyList()

    private fun captureDemo(name: String, caption: String) {
        val directory = System.getenv("GHOST_READER_DEMO_DIR")?.takeIf { it.isNotBlank() } ?: return
        val editor = myFixture.editor
        val component = editor.component
        (editor as EditorEx).setFontSize(20)
        readerInlays().forEach { it.update() }
        component.setSize(1000, 500)
        fun layout(container: Container) { container.doLayout(); container.components.filterIsInstance<Container>().forEach(::layout) }
        layout(component)
        val image = BufferedImage(1040, 600, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            g.color = Color(235, 239, 246); g.fillRect(0, 0, image.width, image.height)
            g.color = Color(35, 45, 62)
            g.font = com.intellij.util.ui.UIUtil.getFontWithFallback(Font("Dialog", Font.BOLD, 20))
            g.drawString("Ghost Reader 0.3.0  /  PyCharm", 22, 34)
            val surface = g.create(20, 52, 1000, 500)
            try { component.paint(surface) } finally { surface.dispose() }
            g.font = com.intellij.util.ui.UIUtil.getFontWithFallback(Font("Dialog", Font.PLAIN, 18))
            g.drawString(caption, 22, 580)
        } finally { g.dispose() }
        Files.createDirectories(Path.of(directory))
        ImageIO.write(image, "png", Path.of(directory, "$name.png").toFile())
    }

    fun testCaretFollowingAndFixedLineMode() {
        val controller = loadNovel("小说甲\n小说乙\n小说丙\n小说丁\n小说戊")
        val editor = myFixture.editor
        assertTrue(service<ReaderSettings>().state.followCaret)
        editor.caretModel.moveToOffset(editor.document.getLineStartOffset(1) + 4)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals(editor.document.getLineEndOffset(1), readerInlays().single().offset)
        editor.caretModel.moveToOffset(editor.document.getLineStartOffset(3))
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals(editor.document.getLineEndOffset(3), readerInlays().single().offset)
        val settings = service<ReaderSettings>().state
        settings.followCaret = false
        settings.anchorLine = 2
        controller.render()
        editor.caretModel.moveToOffset(0)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals(editor.document.getLineEndOffset(1), readerInlays().single().offset)
    }

    fun testWordNavigationReadsWhenVisibleAndEditsWhenHidden() {
        val text = "第1章 起点\n甲\n乙\n丙\n丁\n戊\n第2章 中途\n己\n庚\n辛\n壬\n癸\n第3章 终点\n结束"
        val controller = loadNovel(text)
        val editor = myFixture.editor
        val source = editor.document.text
        val stamp = editor.document.modificationStamp
        val settings = service<ReaderSettings>().state
        val caret = editor.caretModel.offset
        myFixture.performEditorAction("EditorNextWord")
        assertTrue(settings.offsets.values.single() > 0)
        assertEquals(caret, editor.caretModel.offset)
        myFixture.performEditorAction("EditorPreviousWord")
        assertEquals(0, settings.offsets.values.single())
        myFixture.performEditorAction("EditorNextWordWithSelection")
        assertEquals(text.indexOf("第2章"), settings.offsets.values.single())
        assertFalse(editor.selectionModel.hasSelection())
        myFixture.performEditorAction("EditorPreviousWordWithSelection")
        assertEquals(0, settings.offsets.values.single())
        assertEquals(caret, editor.caretModel.offset)
        controller.toggle()
        myFixture.performEditorAction("EditorNextWord")
        assertTrue(editor.caretModel.offset > caret)
        myFixture.performEditorAction("EditorPreviousWord")
        assertEquals(caret, editor.caretModel.offset)
        myFixture.performEditorAction("EditorNextWordWithSelection")
        assertTrue(editor.selectionModel.hasSelection())
        assertEquals(0, settings.offsets.values.single())
        assertEquals(source, editor.document.text)
        assertEquals(stamp, editor.document.modificationStamp)
    }

    fun testChineseFallbackFontCanDisplayAndPaintAllCharacters() {
        loadNovel("中文字体显示测试\n冬日里的书店与灯光\n翻过四行，继续阅读。\nASCII 123 与中文混排")
        val editor = myFixture.editor
        val scheme = EditorColorsSchemeImpl(editor.colorsScheme)
        scheme.editorFontName = "Consolas"
        (editor as EditorEx).setColorsScheme(scheme)
        val font = commentFont(editor)
        assertEquals("Fallback font must contain CJK glyphs", -1, font.canDisplayUpTo("中文字体冬日重现，混排。"))
        val inlay = readerInlays().single()
        val width = inlay.renderer.calcWidthInPixels(inlay) + 24
        val height = inlay.renderer.calcHeightInPixels(inlay) + 24
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = scheme.defaultBackground
            graphics.fillRect(0, 0, width, height)
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            inlay.renderer.paint(inlay, graphics as java.awt.Graphics, Rectangle(12, 12, width - 24, height - 24), TextAttributes())
        } finally { graphics.dispose() }
        val background = scheme.defaultBackground.rgb
        assertTrue((0 until width).any { x -> (0 until height).any { y -> image.getRGB(x, y) != background } })
        System.getenv("GHOST_READER_PREVIEW_PATH")?.takeIf { it.isNotBlank() }?.let {
            val output = Path.of(it)
            Files.createDirectories(output.toAbsolutePath().parent)
            ImageIO.write(image, "png", output.toFile())
        }
        assertEquals(editor.colorsScheme.getFont(EditorFontType.PLAIN).size2D, font.size2D)
    }

    private fun loadNovel(text: String): ReaderController {
        myFixture.configureByText("demo.py", "def greet(name):\n    return 'hello ' + name\n\nprint(greet('reader'))\n")
        novel = Files.createTempFile("ghost-reader-test-", ".txt")
        Files.writeString(novel!!, text)
        service<ReaderSettings>().state.apply { novelPath = novel.toString(); visible = true }
        return project.service<ReaderController>().also { controller ->
            controller.reload()
            PlatformTestUtil.waitWithEventsDispatching("Reader did not create its block inlay", { readerInlays().size == 1 }, 15)
        }
    }

    private fun readerInlays(): List<Inlay<*>> = myFixture.editor.inlayModel
        .getBlockElementsInRange(0, myFixture.editor.document.textLength).filter { it.renderer is CommentRenderer }
}

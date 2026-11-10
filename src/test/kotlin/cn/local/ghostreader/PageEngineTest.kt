package cn.local.ghostreader

import org.junit.Assert.*
import org.junit.Test

class PageEngineTest {
    @Test fun `four lines advance and reverse without losing text`() {
        val text = (1..11).joinToString("\n") { "line $it" }
        val engine = PageEngine(text, 72)
        val first = engine.pageAt(0)
        val second = engine.pageAt(first.next)
        val third = engine.pageAt(second.next)
        assertEquals(listOf("line 1", "line 2", "line 3", "line 4"), first.lines)
        assertEquals(listOf("line 5", "line 6", "line 7", "line 8"), second.lines)
        assertEquals(listOf("line 9", "line 10", "line 11", ""), third.lines)
        assertEquals(first.offset, second.previous)
        assertEquals(second.offset, third.previous)
        assertEquals(third.offset, third.next)
        assertEquals(first.offset, first.previous)
        assertTrue(third.last)
    }

    @Test fun `Chinese wraps at display columns`() {
        val engine = PageEngine("春".repeat(25), 20)
        assertEquals(listOf("春".repeat(10), "春".repeat(10), "春".repeat(5), ""), engine.pageAt(0).lines)
    }

    @Test fun `emoji is never split into surrogate halves`() {
        val text = "🙂".repeat(43)
        val engine = PageEngine(text, 20)
        val first = engine.pageAt(0)
        val second = engine.pageAt(first.next)
        assertEquals(text, (first.lines + second.lines).joinToString(""))
        assertEquals(80, second.offset)
        assertEquals(first.offset, second.previous)
    }

    @Test fun `blank lines and full last pages have stable boundaries`() {
        val engine = PageEngine("a\n\nb\nc\n", 72)
        assertEquals(listOf("a", "b", "c", ""), engine.pageAt(0).lines)
        assertTrue(engine.pageAt(0).last)
        assertEquals(0, engine.pageAt(0).next)
    }

    @Test fun `saved offset restores after reconstruction and width change`() {
        val text = "山".repeat(200)
        val original = PageEngine(text, 20)
        val second = original.pageAt(original.pageAt(0).next)
        assertEquals(second, PageEngine(text, 20).pageAt(second.offset))
        val changed = PageEngine(text, 24).pageAt(second.offset)
        assertTrue(changed.offset <= second.offset)
        assertTrue(second.offset - changed.offset < 12)
        assertTrue(original.pageAt(Int.MAX_VALUE).last)
        assertEquals(0, original.pageAt(-200).offset)
    }

    @Test fun `BOM line endings and tabs normalize consistently`() {
        assertEquals("a\nb\nc    d", PageEngine.normalize("\uFEFFa\r\nb\rc\td\u0000"))
        assertEquals(listOf("", "", "", ""), PageEngine("", 72).pageAt(0).lines)
    }

    @Test fun `whitespace-only paragraphs never consume a reading line`() {
        val text = "\n  \n\u3000\nA\n\nB\n" + " ".repeat(100) + "\nC\n\nD\n\nE\n"
        val engine = PageEngine(text, 20)
        val first = engine.pageAt(0)
        assertEquals(listOf("A", "B", "C", "D"), first.lines)
        assertEquals(text.indexOf('A'), first.offset)
        assertEquals(listOf("E", "", "", ""), engine.pageAt(first.next).lines)
        assertEquals(first.offset, engine.pageAt(first.next).previous)
    }

    @Test fun `old progress on an empty paragraph resumes at next readable line`() {
        val text = "A\n\n   \nB\nC\nD\nE"
        val engine = PageEngine(text, 20)
        assertEquals(text.indexOf('B'), engine.pageAt(3).offset)
        assertEquals(listOf("B", "C", "D", "E"), engine.pageAt(3).lines)
    }

    @Test fun `chapter jumps use the next heading and previous chapter start`() {
        val text = "第1章 开始\n一\n二\n三\n四\n五\n\n第2章 继续\n六\n七\n八\n九\n十\n第3章 结尾\n十一"
        val engine = PageEngine(text, 72)
        val second = text.indexOf("第2章")
        val third = text.indexOf("第3章")
        assertEquals(3, engine.chapters.size)
        assertEquals(second, engine.chapterOffset(0, true))
        assertEquals(third, engine.chapterOffset(second, true))
        assertEquals(0, engine.chapterOffset(second, false))
        assertEquals(second, engine.chapterOffset(text.indexOf("十一"), false))
        assertEquals(third, engine.chapterOffset(third, true))
        assertEquals(0, engine.chapterOffset(0, false))
    }

    @Test fun `Chinese numerals prologues and English chapter headings are recognized`() {
        val text = "介绍\n楔子\n序\n第一百二十三章 标题\n文\n第１２４章 全角\n文\nChapter IV: A New Day\nend"
        val engine = PageEngine(text, 72)
        assertEquals(listOf("楔子", "第一百二十三章 标题", "第１２４章 全角", "Chapter IV: A New Day"), engine.chapters.map { it.title })
        assertEquals(text.indexOf("楔子"), engine.chapterOffset(0, true))
        assertEquals(0, engine.chapterOffset(text.indexOf("楔子"), false))
    }

    @Test fun `novels without headings keep current progress on chapter keys`() {
        val engine = PageEngine("内容\n正文\n下一段\n句子\n末段", 20)
        val offset = engine.pageAt(0).next
        assertTrue(engine.chapters.isEmpty())
        assertEquals(offset, engine.chapterOffset(offset, true))
        assertEquals(offset, engine.chapterOffset(offset, false))
    }

    @Test fun `page row count and width can change while preserving position`() {
        val text = (1..20).joinToString("\n") { "内容${it}：" + "山".repeat(16) }
        val short = PageEngine(text, 20, 2)
        assertEquals(2, short.pageAt(0).lines.size)
        val offset = short.pageAt(0).next
        val wide = PageEngine(text, 80, 6)
        assertEquals(6, wide.pageAt(offset).lines.size)
        assertEquals(short.pageAt(0).offset, short.pageAt(offset).previous)
        assertEquals(1, PageEngine(text, 72, 0).pageLines)
        assertEquals(20, PageEngine(text, 72, 100).pageLines)
    }
}

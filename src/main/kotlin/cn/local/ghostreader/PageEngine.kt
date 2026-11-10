package cn.local.ghostreader

/** All positions are UTF-16 offsets in normalized text, never document offsets. */
class PageEngine @JvmOverloads constructor(val text: String, val columns: Int, pageLines: Int = PAGE_LINES) {
    val pageLines: Int = pageLines.coerceIn(1, 20)
    data class Line(val start: Int, val end: Int, val text: String)
    data class Page(val offset: Int, val lines: List<String>, val previous: Int, val next: Int, val last: Boolean)
    data class Chapter(val offset: Int, val title: String)

    private val lines: List<Line> = wrap(text, columns.coerceIn(20, 160))
    val chapters: List<Chapter> = findChapters(text)

    fun pageAt(offset: Int): Page {
        val index = lineIndex(offset)
        val last = index + pageLines >= lines.size
        val content = lines.subList(index, (index + pageLines).coerceAtMost(lines.size)).map { it.text }.toMutableList()
        while (content.size < pageLines) content.add("")
        return Page(lines[index].start, content, lines[(index - pageLines).coerceAtLeast(0)].start,
            if (last) lines[index].start else lines[index + pageLines].start, last)
    }

    fun chapterOffset(offset: Int, forward: Boolean): Int {
        val pageOffset = pageAt(offset).offset
        if (chapters.isEmpty()) return pageOffset
        val found = chapters.binarySearchBy(pageOffset) { it.offset }
        val current = if (found >= 0) found else -found - 2
        return if (forward) chapters.getOrNull(current + 1)?.offset ?: pageOffset
            else if (current > 0) chapters[current - 1].offset else pageAt(0).offset
    }

    private fun lineIndex(offset: Int): Int {
        val bounded = offset.coerceIn(0, text.length)
        val found = lines.binarySearchBy(bounded) { it.start }
        if (found >= 0) return found
        val insertion = -found - 1
        val previous = insertion - 1
        // A saved position inside skipped whitespace resumes at the next readable line.
        return if (previous >= 0 && bounded < lines[previous].end) previous
            else insertion.coerceAtMost(lines.lastIndex)
    }

    companion object {
        const val PAGE_LINES = 4
        private val chapterTitle = Regex(
            "^(?:第[0-9０-９〇零一二三四五六七八九十百千万两壹贰叁肆伍陆柒捌玖拾佰仟]{1,20}\\s*[章回节卷部篇集].*|" +
                "(?:序章|序言|前言|楔子|引子|引言|尾声|终章|后记|番外).*|chapter\\s+(?:[0-9]+|[ivxlcdm]+)(?:\\b|[.:：]).*)$",
            RegexOption.IGNORE_CASE
        )

        private fun findChapters(text: String): List<Chapter> {
            val result = ArrayList<Chapter>()
            var start = 0
            while (start < text.length) {
                val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
                val title = text.substring(start, end).trim()
                if (title.length in 1..160 && chapterTitle.matches(title)) result.add(Chapter(start, title))
                start = end + 1
            }
            return result
        }

        fun normalize(text: String): String = text.removePrefix("\uFEFF")
            .replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ")
            .filter { it == '\n' || !Character.isISOControl(it) }

        private fun wrap(text: String, columns: Int): List<Line> {
            val result = ArrayList<Line>()
            var start = 0
            var cursor = 0
            var width = 0
            fun addLine(end: Int, next: Int) {
                val content = text.substring(start, end)
                if (content.isNotBlank()) result.add(Line(start, next, content))
            }
            while (cursor < text.length) {
                if (text[cursor] == '\n') {
                    addLine(cursor, cursor + 1)
                    cursor++
                    start = cursor
                    width = 0
                    continue
                }
                val codePoint = text.codePointAt(cursor)
                val nextWidth = displayWidth(codePoint)
                if (width + nextWidth > columns && cursor > start) {
                    addLine(cursor, cursor)
                    start = cursor
                    width = 0
                }
                width += nextWidth
                cursor += Character.charCount(codePoint)
            }
            if (start < text.length) addLine(text.length, text.length)
            if (result.isEmpty()) result.add(Line(0, 0, ""))
            return result
        }

        private fun displayWidth(codePoint: Int): Int {
            val type = Character.getType(codePoint)
            if (type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt()) return 0
            return if (codePoint in 0x1100..0x115F || codePoint in 0x2E80..0xA4CF ||
                codePoint in 0xAC00..0xD7A3 || codePoint in 0xF900..0xFAFF ||
                codePoint in 0xFE10..0xFE6F || codePoint in 0xFF01..0xFF60 || codePoint >= 0x1F000) 2 else 1
        }
    }
}

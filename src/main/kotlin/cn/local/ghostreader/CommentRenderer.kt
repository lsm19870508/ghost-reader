package cn.local.ghostreader

import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.util.ui.UIUtil
import java.awt.Font
import java.awt.Graphics
import java.awt.Rectangle

/** Pure painting: never obtains a write action or changes the editor document. */
class CommentRenderer(private val lines: List<String>, private val prefix: String, private val indent: Int) : EditorCustomElementRenderer {
    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val editor = inlay.editor
        val metrics = editor.contentComponent.getFontMetrics(commentFont(editor))
        return (indent * metrics.charWidth(' ') + lines.maxOf { metrics.stringWidth(prefix + it) } + 8).coerceAtLeast(1)
    }

    override fun calcHeightInPixels(inlay: Inlay<*>): Int = inlay.editor.lineHeight * lines.size.coerceAtLeast(1)

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
        val editor = inlay.editor
        val scheme = editor.colorsScheme
        val copy = g.create()
        try {
            copy.font = commentFont(editor)
            copy.color = scheme.getAttributes(DefaultLanguageHighlighterColors.LINE_COMMENT)?.foregroundColor
                ?: scheme.defaultForeground
            val metrics = copy.fontMetrics
            val x = targetRegion.x + indent * metrics.charWidth(' ')
            val baseline = targetRegion.y + (editor.lineHeight - metrics.height) / 2 + metrics.ascent
            lines.forEachIndexed { index, line -> copy.drawString(prefix + line, x, baseline + index * editor.lineHeight) }
        } finally {
            copy.dispose()
        }
    }
}

/** A physical code font often lacks CJK glyphs; the composite font retains it and adds fallback faces. */
internal fun commentFont(editor: Editor): Font = UIUtil.getFontWithFallback(editor.colorsScheme.getFont(EditorFontType.PLAIN))

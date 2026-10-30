package com.circaso.designsystem.components

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * The little Markdown a model writes (bold, italics, inline code, bullet and numbered lists, headings) as styled text
 * for a bubble. Anything else stays as written: a bubble is not a document, and an unknown mark is better shown than
 * dropped. Links keep their text.
 */
fun markdownToAnnotated(markdown: String, codeBackground: androidx.compose.ui.graphics.Color): AnnotatedString = buildAnnotatedString {
    val lines = markdown.trimEnd().lines()
    lines.forEachIndexed { index, raw ->
        var line = raw
        var heading = false
        Regex("^(#{1,6})\\s+(.*)$").matchEntire(line)?.let { heading = true; line = it.groupValues[2] }
        Regex("^(\\s*)([-*•])\\s+(.*)$").matchEntire(line)?.let { m ->
            line = m.groupValues[1].replace("\t", "    ").let { if (it.length >= 2) "    " else "" } + "•  " + m.groupValues[3]
        }
        Regex("^(\\s*)(\\d+)[.)]\\s+(.*)$").matchEntire(line)?.let { m -> line = "${m.groupValues[2]}.  ${m.groupValues[3]}" }
        if (heading) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { inline(line, codeBackground) } else inline(line, codeBackground)
        if (index < lines.lastIndex) append('\n')
    }
}

/** `**bold**`, `*italic*` / `_italic_`, `` `code` `` and `[text](url)` inside one line. */
private fun AnnotatedString.Builder.inline(text: String, codeBackground: androidx.compose.ui.graphics.Color) {
    val pattern = Regex("(\\*\\*(.+?)\\*\\*)|(`([^`]+)`)|(\\[([^\\]]+)]\\([^)]*\\))|((?<![\\w*])\\*([^*\\s][^*]*?)\\*(?!\\w))|((?<!\\w)_([^_\\s][^_]*?)_(?!\\w))")
    var at = 0
    for (m in pattern.findAll(text)) {
        append(text.substring(at, m.range.first))
        when {
            m.groups[2] != null -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(m.groups[2]!!.value) }
            m.groups[4] != null -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) { append(m.groups[4]!!.value) }
            m.groups[6] != null -> append(m.groups[6]!!.value)
            m.groups[8] != null -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(m.groups[8]!!.value) }
            m.groups[10] != null -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(m.groups[10]!!.value) }
        }
        at = m.range.last + 1
    }
    append(text.substring(at))
}

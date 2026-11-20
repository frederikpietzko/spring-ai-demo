package dev.frederikpietzko.springaidemo.desktop.chat

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/** A line that is a list item: `- item` or `* item`. */
private val BULLET = Regex("""^\s*[-*] (.*)$""")

/** `**bold**`, `` `code` `` and `*italics*`; a `*` next to a space (as in `4 * 3`) is not italics. */
private val INLINE = Regex("""\*\*(.+?)\*\*|`([^`]+)`|\*(\S(?:[^*]*?\S)?)\*""")

/**
 * Renders the little Markdown models write in chat replies: bold, italics, inline code and bullet lists. The markers
 * are dropped; everything else stays plain text (no headings, links or tables).
 */
fun markdown(text: String): AnnotatedString = buildAnnotatedString {
    text.lines().forEachIndexed { index, line ->
        if (index > 0) append('\n')
        val bullet = BULLET.matchEntire(line)
        val content = if (bullet != null) "• " + bullet.groupValues[1] else line
        var start = 0
        INLINE.findAll(content).forEach { match ->
            append(content.substring(start, match.range.first))
            val bold = match.groups[1]?.value
            val code = match.groups[2]?.value
            when {
                bold != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold) }
                code != null -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(code) }
                else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(match.groupValues.last()) }
            }
            start = match.range.last + 1
        }
        append(content.substring(start))
    }
}

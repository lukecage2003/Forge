package ai.forge.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/** Mise en forme légère des réponses : **gras**, `code`, titres « # », puces « - ». */
object RichText {
    fun render(md: String): AnnotatedString = buildAnnotatedString {
        md.lines().forEachIndexed { i, raw ->
            if (i > 0) append('\n')
            var line = raw
            val heading = Regex("^#{1,4}\\s+").find(line)
            when {
                heading != null -> { withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = ForgePalette.GoldSoft)) { inline(line.substring(heading.range.last + 1)) }; return@forEachIndexed }
                line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> {
                    append("  •  "); line = line.trimStart().drop(2)
                }
            }
            inline(line)
        }
    }

    private fun AnnotatedString.Builder.inline(text: String) {
        val rx = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`")
        var last = 0
        rx.findAll(text).forEach { m ->
            append(text.substring(last, m.range.first))
            m.groups[1]?.let { withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Color.White)) { append(it.value) } }
            m.groups[2]?.let { withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color.White.copy(alpha = 0.08f))) { append(it.value) } }
            last = m.range.last + 1
        }
        append(text.substring(last))
    }
}

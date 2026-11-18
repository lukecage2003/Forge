package ai.forge.core.export

import ai.forge.core.editorial.ScriptDocument

/**
 * Exports textuels (Markdown, TXT). Les exports PDF et DOCX sont réalisés côté Android
 * (PdfDocument natif ; DOCX via génération OOXML) à partir du Markdown produit ici.
 */
object Exporters {
    fun scriptToMarkdown(s: ScriptDocument): String = buildString {
        appendLine("# ${s.title}")
        appendLine()
        appendLine("| | |")
        appendLine("|---|---|")
        appendLine("| **Promesse** | ${s.promise} |")
        appendLine("| **Public** | ${s.audience} |")
        appendLine("| **Format** | ${s.format.label} |")
        appendLine("| **Durée visée** | ${s.format.targetSeconds / 60} min ${s.format.targetSeconds % 60} s |")
        appendLine("| **Durée estimée** | ${s.estimatedSeconds / 60} min ${s.estimatedSeconds % 60} s (${s.wordCount} mots) |")
        appendLine("| **Angle** | ${s.angle} |")
        appendLine()
        s.segments.forEachIndexed { i, seg ->
            appendLine("## ${i + 1}. ${seg.heading}")
            appendLine()
            appendLine(seg.narration)
            appendLine()
            seg.deliveryNotes?.let { appendLine("> 🎙️ *Narration :* $it\n") }
            if (seg.visuals.isNotEmpty()) appendLine("> 🎬 *Visuels :* ${seg.visuals.joinToString(" · ")}\n")
            if (seg.claimIds.isNotEmpty()) appendLine("> 📚 *Affirmations :* ${seg.claimIds.joinToString(", ")}\n")
            seg.transition?.let { appendLine("*Transition :* $it\n") }
        }
        if (s.sources.isNotEmpty()) {
            appendLine("## Sources")
            s.sources.forEach { appendLine("- $it") }
        }
    }.trimEnd() + "\n"

    fun markdownToPlainText(md: String): String = md.lines().joinToString("\n") { line ->
        line.replace(Regex("^#+\\s*"), "")
            .replace(Regex("^>\\s*"), "")
            .replace(Regex("\\*\\*|\\*|`"), "")
            .replace(Regex("\\[([^]]+)]\\(([^)]+)\\)"), "$1 ($2)")
    }
}

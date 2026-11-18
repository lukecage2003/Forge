package ai.forge.writing

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Bloc de document issu du Markdown léger produit par Forge. */
sealed interface DocBlock {
    data class Heading(val level: Int, val text: String) : DocBlock
    data class Paragraph(val runs: List<Run>) : DocBlock
    data class Bullet(val runs: List<Run>, val ordered: Int? = null) : DocBlock
    data class Table(val rows: List<List<String>>) : DocBlock
    data object Rule : DocBlock
}

data class Run(val text: String, val bold: Boolean = false, val italic: Boolean = false, val code: Boolean = false)

/** Analyse du Markdown courant (titres, gras, italique, code, listes, tableaux, séparateurs). */
object MarkdownDoc {
    fun parse(md: String): List<DocBlock> {
        val out = mutableListOf<DocBlock>()
        val lines = md.replace("\r", "").lines()
        var i = 0
        val para = StringBuilder()
        fun flush() { if (para.isNotBlank()) out += DocBlock.Paragraph(runs(para.toString().trim())); para.clear() }
        while (i < lines.size) {
            val l = lines[i].trimEnd()
            val h = Regex("^(#{1,6})\\s+(.*)$").find(l)
            when {
                l.isBlank() -> flush()
                h != null -> { flush(); out += DocBlock.Heading(h.groupValues[1].length, stripInline(h.groupValues[2])) }
                Regex("^\\s*([-*_])\\1{2,}\\s*$").matches(l) -> { flush(); out += DocBlock.Rule }
                Regex("^\\s*[-*•]\\s+").containsMatchIn(l) -> { flush(); out += DocBlock.Bullet(runs(l.replace(Regex("^\\s*[-*•]\\s+"), ""))) }
                Regex("^\\s*\\d+[.)]\\s+").containsMatchIn(l) -> { flush()
                    val n = Regex("^\\s*(\\d+)").find(l)!!.groupValues[1].toInt()
                    out += DocBlock.Bullet(runs(l.replace(Regex("^\\s*\\d+[.)]\\s+"), "")), n) }
                l.trimStart().startsWith("|") -> { flush()
                    val rows = mutableListOf<List<String>>()
                    while (i < lines.size && lines[i].trimStart().startsWith("|")) {
                        val cells = lines[i].trim().trim('|').split("|").map { stripInline(it.trim()) }
                        if (!cells.all { Regex("^:?-{2,}:?$").matches(it) }) rows += cells
                        i++
                    }
                    out += DocBlock.Table(rows); continue
                }
                else -> { if (para.isNotEmpty()) para.append(' '); para.append(l.trim()) }
            }
            i++
        }
        flush()
        return out
    }

    fun runs(text: String): List<Run> {
        val rx = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`|(?<![*\\w])\\*([^*]+)\\*(?![*\\w])|_([^_]+)_")
        val out = mutableListOf<Run>(); var last = 0
        rx.findAll(text).forEach { m ->
            if (m.range.first > last) out += Run(text.substring(last, m.range.first))
            when {
                m.groups[1] != null -> out += Run(m.groupValues[1], bold = true)
                m.groups[2] != null -> out += Run(m.groupValues[2], code = true)
                m.groups[3] != null -> out += Run(m.groupValues[3], italic = true)
                else -> out += Run(m.groupValues[4], italic = true)
            }
            last = m.range.last + 1
        }
        if (last < text.length) out += Run(text.substring(last))
        return out.filter { it.text.isNotEmpty() }
    }

    fun stripInline(t: String) = runs(t).joinToString("") { it.text }

    fun plainText(md: String): String = parse(md).joinToString("\n\n") { b ->
        when (b) {
            is DocBlock.Heading -> b.text.uppercase()
            is DocBlock.Paragraph -> b.runs.joinToString("") { it.text }
            is DocBlock.Bullet -> (b.ordered?.let { "$it. " } ?: "• ") + b.runs.joinToString("") { it.text }
            is DocBlock.Table -> b.rows.joinToString("\n") { it.joinToString("  |  ") }
            DocBlock.Rule -> "———"
        }
    }
}

/**
 * Générateur DOCX (Office Open XML) sans dépendance : ouvrable dans Word, Google Docs,
 * LibreOffice et les applications Android. Titres, gras, italique, listes, tableaux.
 */
object DocxWriter {
    fun write(title: String, md: String, author: String = "Forge"): ByteArray {
        val body = StringBuilder()
        body.append(p(title, style = "Title"))
        MarkdownDoc.parse(md).forEach { b ->
            when (b) {
                is DocBlock.Heading -> body.append(p(b.text, style = "Heading${b.level.coerceIn(1, 3)}"))
                is DocBlock.Paragraph -> body.append(pRuns(b.runs))
                is DocBlock.Bullet -> body.append(pRuns(listOf(Run((b.ordered?.let { "$it. " } ?: "• "))) + b.runs, indent = true))
                is DocBlock.Table -> body.append(table(b.rows))
                DocBlock.Rule -> body.append("<w:p><w:pPr><w:pBdr><w:bottom w:val=\"single\" w:sz=\"6\" w:space=\"1\" w:color=\"C8901A\"/></w:pBdr></w:pPr></w:p>")
            }
        }
        val document = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$body<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1134" w:right="1134" w:bottom="1134" w:left="1134" w:header="709" w:footer="709" w:gutter="0"/></w:sectPr></w:body></w:document>"""
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            fun put(name: String, content: String) { z.putNextEntry(ZipEntry(name)); z.write(content.toByteArray(Charsets.UTF_8)); z.closeEntry() }
            put("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/><Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/></Types>""")
            put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/></Relationships>""")
            put("word/_rels/document.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""")
            put("word/styles.xml", STYLES)
            put("docProps/core.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>${esc(title)}</dc:title><dc:creator>${esc(author)}</dc:creator></cp:coreProperties>""")
            put("word/document.xml", document)
        }
        return out.toByteArray()
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun run(r: Run) = buildString {
        append("<w:r>")
        if (r.bold || r.italic || r.code) {
            append("<w:rPr>"); if (r.bold) append("<w:b/>"); if (r.italic) append("<w:i/>")
            if (r.code) append("<w:rFonts w:ascii=\"Consolas\" w:hAnsi=\"Consolas\"/>"); append("</w:rPr>")
        }
        append("<w:t xml:space=\"preserve\">").append(esc(r.text)).append("</w:t></w:r>")
    }
    private fun p(text: String, style: String? = null) = pRuns(listOf(Run(text)), style)
    private fun pRuns(runs: List<Run>, style: String? = null, indent: Boolean = false) = buildString {
        append("<w:p>")
        if (style != null || indent) { append("<w:pPr>"); style?.let { append("<w:pStyle w:val=\"$it\"/>") }; if (indent) append("<w:ind w:left=\"360\"/>"); append("</w:pPr>") }
        runs.forEach { append(run(it)) }
        append("</w:p>")
    }
    private fun table(rows: List<List<String>>) = buildString {
        append("<w:tbl><w:tblPr><w:tblStyle w:val=\"TableGrid\"/><w:tblW w:w=\"0\" w:type=\"auto\"/><w:tblBorders>")
        listOf("top", "left", "bottom", "right", "insideH", "insideV").forEach { append("<w:$it w:val=\"single\" w:sz=\"4\" w:color=\"BBBBBB\"/>") }
        append("</w:tblBorders></w:tblPr>")
        rows.forEachIndexed { i, r -> append("<w:tr>"); r.forEach { c -> append("<w:tc>").append(pRuns(listOf(Run(c, bold = i == 0)))).append("</w:tc>") }; append("</w:tr>") }
        append("</w:tbl><w:p/>")
    }

    private val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri"/><w:sz w:val="22"/><w:lang w:val="fr-FR"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after="140" w:line="300" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults>
<w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:rPr><w:b/><w:sz w:val="44"/><w:color w:val="C8901A"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:pPr><w:spacing w:before="280"/></w:pPr><w:rPr><w:b/><w:sz w:val="32"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/><w:pPr><w:spacing w:before="220"/></w:pPr><w:rPr><w:b/><w:sz w:val="28"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="Heading3"><w:name w:val="heading 3"/><w:rPr><w:b/><w:sz w:val="24"/></w:rPr></w:style>
<w:style w:type="table" w:styleId="TableGrid"><w:name w:val="Table Grid"/></w:style></w:styles>"""
}

/** Demande explicite de fichier (« fais-moi un PDF… », « en Word »). */
enum class DocFormat(val extension: String, val mime: String, val label: String) {
    PDF("pdf", "application/pdf", "PDF"),
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "Word"),
    MARKDOWN("md", "text/markdown", "Markdown"),
    TXT("txt", "text/plain", "Texte");

    companion object {
        fun requested(text: String): DocFormat? {
            val t = text.lowercase()
            return when {
                Regex("\\bpdf\\b").containsMatchIn(t) -> PDF
                Regex("\\b(docx|word)\\b").containsMatchIn(t) -> DOCX
                Regex("\\bmarkdown\\b").containsMatchIn(t) -> MARKDOWN
                Regex("fichier (texte|txt)|\\.txt\\b").containsMatchIn(t) -> TXT
                else -> null
            }
        }

        /** Titre de fichier lisible tiré de la demande. */
        fun titleFrom(request: String): String = request.replace(Regex("(?i)(fais|crée|cree|génère|genere|rédige|redige|écris|ecris|prépare|prepare)[- ]?(moi)?|un (pdf|document|fichier|word|docx)|en (pdf|word|docx)|\\bpdf\\b|s'il te plaît|stp|sur|de|des|du|le|la|les|un|une"), " ")
            .replace(Regex("[^\\p{L}\\p{N}' -]"), " ").replace(Regex("\\s+"), " ").trim().take(60)
            .replaceFirstChar { it.uppercase() }.ifBlank { "Document Forge" }
    }
}

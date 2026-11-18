package ai.forge.writing

import java.util.zip.ZipInputStream
import kotlin.test.*

class DocumentsTest {
    private val md = """
        # Le vibranium
        Un métal **fictif** qui *absorbe* les vibrations.

        ## Propriétés
        - Absorbe l'énergie cinétique
        - Origine : météorite
        1. Premier point
        | Propriété | Valeur |
        |---|---|
        | Densité | élevée |
        ---
        Fin & conclusion <ok>.
    """.trimIndent()

    @Test fun parsesMarkdownBlocks() {
        val b = MarkdownDoc.parse(md)
        assertEquals(DocBlock.Heading(1, "Le vibranium"), b[0])
        val p = b[1] as DocBlock.Paragraph
        assertEquals(listOf(Run("Un métal "), Run("fictif", bold = true), Run(" qui "), Run("absorbe", italic = true), Run(" les vibrations.")), p.runs)
        assertEquals(2, b.count { it is DocBlock.Bullet && it.ordered == null })
        assertEquals(1, (b.first { it is DocBlock.Bullet && it.ordered != null } as DocBlock.Bullet).ordered)
        val t = b.first { it is DocBlock.Table } as DocBlock.Table
        assertEquals(listOf(listOf("Propriété", "Valeur"), listOf("Densité", "élevée")), t.rows)
        assertTrue(b.contains(DocBlock.Rule))
        assertTrue(MarkdownDoc.plainText(md).startsWith("LE VIBRANIUM"))
    }

    @Test fun docxIsAValidPackageWithEscapedText() {
        val bytes = DocxWriter.write("Dossier vibranium", md)
        val entries = mutableMapOf<String, String>()
        ZipInputStream(bytes.inputStream()).use { z -> generateSequence { z.nextEntry }.forEach { entries[it.name] = z.readBytes().toString(Charsets.UTF_8) } }
        assertTrue(listOf("[Content_Types].xml", "_rels/.rels", "word/document.xml", "word/styles.xml", "word/_rels/document.xml.rels").all { it in entries })
        val doc = entries.getValue("word/document.xml")
        assertTrue(doc.contains("Dossier vibranium")); assertTrue(doc.contains("<w:b/>")); assertTrue(doc.contains("Heading2"))
        assertTrue(doc.contains("Fin &amp; conclusion &lt;ok&gt;.")); assertTrue(doc.contains("<w:tbl>"))
        assertFalse(doc.contains("**"))
    }

    @Test fun detectsRequestedFormatAndTitle() {
        assertEquals(DocFormat.PDF, DocFormat.requested("Fais-moi un PDF sur l'histoire de Wakanda"))
        assertEquals(DocFormat.DOCX, DocFormat.requested("mets-le en Word"))
        assertNull(DocFormat.requested("Parle-moi de Thor"))
        assertEquals("L'histoire Wakanda", DocFormat.titleFrom("Fais-moi un PDF sur l'histoire de Wakanda"))
    }
}

class PdfWriterTest {
    @Test fun producesValidMultiPagePdfWithFrenchText() {
        val md = buildString {
            appendLine("## Origines du Wakanda"); appendLine("Le **vibranium** est arrivé grâce à une météorite ; c'est « l'âme » du pays — œuvre collective.")
            repeat(90) { appendLine("- Point $it : les ressources du Wakanda et leur protection") }
            appendLine("| Élément | Valeur |"); appendLine("|---|---|"); appendLine("| Densité | élevée |")
            appendLine("---"); appendLine("Fin 🚀 (parenthèses) \\ échappées.")
        }
        val pdf = PdfWriter.write("Dossier Wakanda", md, subtitle = "test")
        java.io.File("build/sample.pdf").writeBytes(pdf)
        val s = String(pdf, Charsets.ISO_8859_1)
        assertTrue(s.startsWith("%PDF-1.4")); assertTrue(s.trimEnd().endsWith("%%EOF"))
        val pages = Regex("/Type /Page /Parent").findAll(s).count()
        assertTrue(pages >= 2, "pages=$pages")
        assertTrue(s.contains("/Count $pages"))
        assertTrue(s.contains("(vibranium)") || s.contains("vibranium"))
        assertTrue(s.contains("\\351") || s.contains("é"))       // é encodé en WinAnsi
        assertTrue(s.contains("\\(parenth"))                      // parenthèses échappées
        // xref : chaque décalage pointe bien sur « n 0 obj ».
        val xref = s.substringAfter("xref\n").lines().drop(2).takeWhile { it.endsWith("n ") }.map { it.take(10).toInt() }
        xref.forEachIndexed { i, off -> assertTrue(s.startsWith("${i + 1} 0 obj", off), "objet ${i + 1}") }
    }
}

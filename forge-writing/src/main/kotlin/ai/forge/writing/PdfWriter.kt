package ai.forge.writing

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Générateur PDF autonome (PDF 1.4) : polices standard Helvetica/Courier en WinAnsi (accents
 * français, œ, guillemets), retour à la ligne mesuré, titres, gras, italique, listes, tableaux,
 * filets, pagination A4 avec pied de page. Aucune dépendance : identique sur tous les appareils.
 */
object PdfWriter {
    private const val W = 595f; private const val H = 842f; private const val M = 56f
    private val WIN = Charset.forName("windows-1252")

    private enum class Font(val res: String, val base: String) {
        REG("F1", "Helvetica"), BOLD("F2", "Helvetica-Bold"), ITAL("F3", "Helvetica-Oblique"), MONO("F4", "Courier")
    }

    // Largeurs AFM Helvetica (1/1000 em) pour les caractères 32..126.
    private val HELV = intArrayOf(278,278,355,556,556,889,667,191,333,333,389,584,278,333,278,278,556,556,556,556,556,556,556,556,556,556,
        278,278,584,584,584,556,1015,667,667,722,722,667,611,778,722,278,500,667,556,833,722,778,667,778,722,667,611,722,667,944,667,667,611,
        278,278,278,469,556,333,556,556,500,556,556,278,556,556,222,222,500,222,833,556,556,556,556,333,500,278,556,500,722,500,500,500,334,260,334,584)

    private fun base(c: Char): Char = java.text.Normalizer.normalize(c.toString(), java.text.Normalizer.Form.NFD).firstOrNull() ?: c
    private fun charWidth(c: Char, f: Font): Float {
        if (f == Font.MONO) return 600f
        val b = base(c)
        val w = when { b.code in 32..126 -> HELV[b.code - 32]; c == 'œ' -> 944; c == 'Œ' -> 1000; c == '’' || c == '‘' -> 222; c == '«' || c == '»' -> 556; c == '…' -> 1000; c == '—' -> 1000; c == '–' -> 556; else -> 556 }
        return if (f == Font.BOLD) w * 1.11f else w.toFloat()
    }
    private fun width(s: String, f: Font, size: Float) = s.sumOf { charWidth(it, f).toDouble() }.toFloat() * size / 1000f

    /** Texte encodé WinAnsi et échappé pour un littéral PDF ; caractères hors jeu remplacés. */
    private fun pdfString(s: String): String {
        val norm = s.replace(' ', ' ').replace(' ', ' ')
        // Caractères hors jeu (émojis, symboles rares) : retirés plutôt qu'affichés « ? ».
        val enc = WIN.newEncoder()
        val bytes = norm.filter { enc.canEncode(it) }.toByteArray(WIN)
        val sb = StringBuilder("(")
        bytes.forEach { b -> val i = b.toInt() and 0xff; when { i == 40 || i == 41 || i == 92 -> sb.append('\\').append(i.toChar()); i < 32 || i > 126 -> sb.append("\\").append(Integer.toOctalString(i).padStart(3, '0')); else -> sb.append(i.toChar()) } }
        return sb.append(")").toString()
    }

    private data class Piece(val text: String, val font: Font)

    private class Pager {
        val pages = mutableListOf<StringBuilder>()
        var y = 0f
        val cur get() = pages.last()
        fun newPage() { pages += StringBuilder(); y = H - M }
        fun ensure(h: Float) { if (pages.isEmpty() || y - h < M + 24) newPage() }
    }

    private fun wrap(pieces: List<Piece>, size: Float, maxW: Float): List<List<Piece>> {
        val lines = mutableListOf<MutableList<Piece>>(mutableListOf()); var lineW = 0f
        pieces.forEach { p ->
            // Espace en tête de fragment (« **gras** suite ») : on la garde, sinon les mots se collent.
            if (p.text.firstOrNull()?.isWhitespace() == true && lines.last().isNotEmpty()) { lines.last() += Piece(" ", p.font); lineW += width(" ", p.font, size) }
            Regex("\\S+\\s*").findAll(p.text).forEach { m ->
                val word = m.value; val ww = width(word.trimEnd(), p.font, size); val full = width(word, p.font, size)
                if (lineW + ww > maxW && lines.last().isNotEmpty()) { lines.add(mutableListOf()); lineW = 0f }
                lines.last() += Piece(word, p.font); lineW += full
            }
        }
        return lines.filter { it.isNotEmpty() }
    }

    private fun Pager.text(pieces: List<Piece>, size: Float, x: Float, maxW: Float, leading: Float = size * 1.35f, color: String = "0.1 0.1 0.12") {
        wrap(pieces, size, maxW).forEach { line ->
            ensure(leading)
            y -= leading
            cur.append("BT $color rg ")
            var cx = x
            line.forEach { p ->
                cur.append("/${p.font.res} ${"%.1f".format(Locale.US, size)} Tf 1 0 0 1 ${"%.2f".format(Locale.US, cx)} ${"%.2f".format(Locale.US, y)} Tm ${pdfString(p.text)} Tj ")
                cx += width(p.text, p.font, size)
            }
            cur.append("ET\n")
        }
    }

    private fun runs(r: List<Run>) = r.map { Piece(it.text, when { it.code -> Font.MONO; it.bold -> Font.BOLD; it.italic -> Font.ITAL; else -> Font.REG }) }

    fun write(title: String, md: String, subtitle: String = "Généré par Forge · " + DateFormat.getDateInstance(DateFormat.LONG, Locale.FRANCE).format(Date())): ByteArray {
        val pg = Pager(); pg.newPage()
        val tw = W - 2 * M
        pg.text(listOf(Piece(title, Font.BOLD)), 22f, M, tw, color = "0.78 0.56 0.10")
        pg.text(listOf(Piece(subtitle, Font.REG)), 9f, M, tw, color = "0.5 0.5 0.5"); pg.y -= 12f
        MarkdownDoc.parse(md).forEach { b ->
            when (b) {
                is DocBlock.Heading -> { pg.ensure(40f); pg.y -= 8f; pg.text(listOf(Piece(b.text, Font.BOLD)), when (b.level) { 1 -> 17f; 2 -> 14.5f; else -> 12.5f }, M, tw); pg.y -= 2f }
                is DocBlock.Paragraph -> { pg.text(runs(b.runs), 11f, M, tw); pg.y -= 5f }
                is DocBlock.Bullet -> {
                    pg.ensure(16f)
                    val marker = b.ordered?.let { "$it." } ?: "•"
                    pg.cur.append("BT 0.78 0.56 0.10 rg /F2 11 Tf 1 0 0 1 ${"%.2f".format(Locale.US, M + 2)} ${"%.2f".format(Locale.US, pg.y - 11f * 1.35f)} Tm ${pdfString(marker)} Tj ET\n")
                    pg.text(runs(b.runs), 11f, M + 18f, tw - 18f); pg.y -= 2f
                }
                is DocBlock.Table -> {
                    val cols = b.rows.maxOf { it.size }.coerceAtLeast(1); val cw = tw / cols
                    b.rows.forEachIndexed { ri, row ->
                        val f = if (ri == 0) Font.BOLD else Font.REG
                        val cellLines = (0 until cols).map { wrap(listOf(Piece(row.getOrElse(it) { "" }, f)), 10f, cw - 8f) }
                        val rh = (cellLines.maxOf { it.size }.coerceAtLeast(1)) * 13.5f + 6f
                        pg.ensure(rh)
                        val top = pg.y
                        (0 until cols).forEach { ci ->
                            pg.cur.append("0.75 0.75 0.75 RG 0.5 w ${"%.2f".format(Locale.US, M + ci * cw)} ${"%.2f".format(Locale.US, top - rh)} ${"%.2f".format(Locale.US, cw)} ${"%.2f".format(Locale.US, rh)} re S\n")
                            var ly = top - 3f
                            cellLines[ci].forEach { line ->
                                ly -= 13.5f
                                pg.cur.append("BT 0.1 0.1 0.12 rg /${f.res} 10 Tf 1 0 0 1 ${"%.2f".format(Locale.US, M + ci * cw + 4)} ${"%.2f".format(Locale.US, ly + 3)} Tm ${pdfString(line.joinToString("") { it.text })} Tj ET\n")
                            }
                        }
                        pg.y = top - rh
                    }
                    pg.y -= 10f
                }
                DocBlock.Rule -> { pg.ensure(14f); pg.y -= 7f; pg.cur.append("0.78 0.56 0.10 RG 0.8 w $M ${"%.2f".format(Locale.US, pg.y)} m ${W - M} ${"%.2f".format(Locale.US, pg.y)} l S\n"); pg.y -= 7f }
            }
        }
        return assemble(pg.pages, title)
    }

    private fun assemble(pages: List<StringBuilder>, title: String): ByteArray {
        val objs = mutableListOf<ByteArray>()
        fun add(s: String): Int { objs += s.toByteArray(WIN); return objs.size }
        add("<< /Type /Catalog /Pages 2 0 R >>")
        add("PAGES")   // remplacé plus bas
        val fonts = Font.entries.map { add("<< /Type /Font /Subtype /Type1 /BaseFont /${it.base} /Encoding /WinAnsiEncoding >>") }
        val fontDict = Font.entries.zip(fonts).joinToString(" ") { (f, id) -> "/${f.res} $id 0 R" }
        val pageIds = pages.mapIndexed { i, content ->
            val footer = "BT 0.55 0.55 0.55 rg /F1 8 Tf 1 0 0 1 $M 28 Tm ${pdfString("Forge · $title · page ${i + 1}/${pages.size}")} Tj ET\n"
            val stream = (content.toString() + footer).toByteArray(WIN)
            objs += ("<< /Length ${stream.size} >>\nstream\n").toByteArray(WIN) + stream + "\nendstream".toByteArray(WIN)
            val contentId = objs.size
            add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << $fontDict >> >> /Contents $contentId 0 R >>")
        }
        objs[1] = "<< /Type /Pages /Kids [${pageIds.joinToString(" ") { "$it 0 R" }}] /Count ${pageIds.size} >>".toByteArray(WIN)
        val info = add("<< /Title ${pdfString(title)} /Producer (Forge) >>")
        val out = ByteArrayOutputStream()
        out.write("%PDF-1.4\n%âãÏÓ\n".toByteArray(WIN))
        val offsets = objs.mapIndexed { i, o -> out.size().also { out.write("${i + 1} 0 obj\n".toByteArray(WIN)); out.write(o); out.write("\nendobj\n".toByteArray(WIN)) } }
        val xref = out.size()
        out.write("xref\n0 ${objs.size + 1}\n0000000000 65535 f \n".toByteArray(WIN))
        offsets.forEach { out.write("%010d 00000 n \n".format(it).toByteArray(WIN)) }
        out.write("trailer\n<< /Size ${objs.size + 1} /Root 1 0 R /Info $info 0 R >>\nstartxref\n$xref\n%%EOF\n".toByteArray(WIN))
        return out.toByteArray()
    }
}

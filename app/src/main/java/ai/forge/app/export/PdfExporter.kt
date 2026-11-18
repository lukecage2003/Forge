package ai.forge.app.export

import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File

/** Export PDF natif (A4) d'un texte brut ou issu du Markdown (Exporters.markdownToPlainText). */
object PdfExporter {
    fun export(text: String, out: File, title: String? = null): File {
        val doc = PdfDocument()
        val pageW = 595; val pageH = 842; val margin = 50
        val paint = TextPaint().apply { textSize = 11f; isAntiAlias = true }
        val full = (title?.let { "$it\n\n" } ?: "") + text
        val layout = StaticLayout.Builder.obtain(full, 0, full.length, paint, pageW - 2 * margin)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(2f, 1.1f).build()
        val usable = pageH - 2 * margin
        var line = 0; var pageNo = 1
        while (line < layout.lineCount) {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo++).create())
            val startTop = layout.getLineTop(line)
            var end = line
            while (end < layout.lineCount && layout.getLineBottom(end) - startTop <= usable) end++
            val c = page.canvas
            c.save(); c.translate(margin.toFloat(), (margin - startTop).toFloat())
            c.clipRect(0, startTop, pageW - 2 * margin, layout.getLineBottom(end - 1))
            layout.draw(c); c.restore()
            doc.finishPage(page)
            line = end
        }
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
        return out
    }
}

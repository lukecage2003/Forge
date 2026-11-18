package ai.forge.app.export

import ai.forge.writing.DocBlock
import ai.forge.writing.DocFormat
import ai.forge.writing.DocxWriter
import ai.forge.writing.MarkdownDoc
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.DateFormat
import java.util.Date

/** Création de fichiers (PDF, Word, Markdown, texte) dans Téléchargements/Forge, puis partage. */
object DocumentExporter {
    suspend fun export(context: Context, format: DocFormat, title: String, markdown: String): Uri = withContext(Dispatchers.IO) {
        val bytes = when (format) {
            DocFormat.PDF -> ai.forge.writing.PdfWriter.write(title, markdown)
            DocFormat.DOCX -> DocxWriter.write(title, markdown)
            DocFormat.MARKDOWN -> "# $title\n\n$markdown".toByteArray()
            DocFormat.TXT -> (title + "\n\n" + MarkdownDoc.plainText(markdown)).toByteArray()
        }
        val safe = title.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().take(60).ifBlank { "Document Forge" }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "$safe.${format.extension}")
            put(MediaStore.Downloads.MIME_TYPE, format.mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Forge")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
        context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
        uri
    }

    /** Fichier quelconque (sous-titres SRT…) dans Téléchargements/Forge. */
    suspend fun exportRaw(context: Context, fileName: String, mime: String, bytes: ByteArray): Uri = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName); put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Forge")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
        context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
        uri
    }

    fun share(context: Context, uri: Uri, mime: String) = context.startActivity(Intent.createChooser(
        Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Partager")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    fun open(context: Context, uri: Uri, format: DocFormat) {
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, format.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val share = Intent(Intent.ACTION_SEND).setType(format.mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(share, "Ouvrir ou partager").putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(view))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

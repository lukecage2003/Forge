package ai.forge.app.academy

import ai.forge.academy.Course
import ai.forge.academy.CourseLibrary
import ai.forge.academy.Flashcard
import ai.forge.academy.FlashcardExtractor
import ai.forge.academy.SpacedRepetition
import ai.forge.app.library.LibraryRepository
import ai.forge.app.memory.KvDao
import ai.forge.app.memory.KvRow
import ai.forge.documents.OcrEngine
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.UUID

/**
 * Cours et fiches : import PDF (rendu des pages + OCR sur l'appareil), découpage cité par page,
 * fiches extraites des définitions, révision espacée SM-2. Les pages du cours sont aussi indexées
 * dans la bibliothèque pour que Forge les cite quand vous posez une question d'études.
 */
class AcademyRepository(private val kv: KvDao, private val ocr: OcrEngine, private val library: LibraryRepository) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun courses(): List<Course> = kv.all(COURSES).mapNotNull { runCatching { json.decodeFromString(Course.serializer(), it.json) }.getOrNull() }
    suspend fun cards(courseId: String): List<Flashcard> = kv.get(CARDS, courseId)?.let { json.decodeFromString(ListSerializer(Flashcard.serializer()), it.json) }.orEmpty()
    suspend fun due(courseId: String, today: LocalDate = LocalDate.now()) = SpacedRepetition.due(cards(courseId), today)

    suspend fun importPdf(context: Context, uri: Uri, title: String, onProgress: (Int, Int) -> Unit): Course = withContext(Dispatchers.IO) {
        val id = "cours-" + UUID.randomUUID().toString().take(8)
        val pages = mutableListOf<String>()
        context.contentResolver.openFileDescriptor(uri, "r")!!.use { fd ->
            PdfRenderer(fd).use { pdf ->
                for (i in 0 until pdf.pageCount) {
                    onProgress(i + 1, pdf.pageCount)
                    pdf.openPage(i).use { page ->
                        val scale = 1600f / maxOf(page.width, page.height)
                        val bmp = Bitmap.createBitmap((page.width * scale).toInt(), (page.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val png = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it); bmp.recycle() }.toByteArray()
                        pages += ocr.recognize(png).text
                    }
                }
            }
        }
        val course = Course(id, title, title, CourseLibrary.chunk(id, pages))
        kv.put(KvRow(COURSES, id, json.encodeToString(Course.serializer(), course), System.currentTimeMillis()))
        val cards = course.chunks.flatMap { FlashcardExtractor.extract(it, title) }.distinctBy { it.notion.lowercase() }
        saveCards(id, cards)
        // Pages citables par l'orchestrateur (questions d'études).
        pages.forEachIndexed { i, text -> if (text.isNotBlank()) library.addCoursePage(id, title, i + 1, text) }
        course
    }

    suspend fun review(courseId: String, card: Flashcard, grade: Int) {
        saveCards(courseId, cards(courseId).map { if (it.id == card.id) SpacedRepetition.review(it, grade, LocalDate.now()) else it })
    }

    suspend fun delete(courseId: String) { kv.delete(COURSES, courseId); kv.delete(CARDS, courseId) }

    private suspend fun saveCards(courseId: String, cards: List<Flashcard>) =
        kv.put(KvRow(CARDS, courseId, json.encodeToString(ListSerializer(Flashcard.serializer()), cards), System.currentTimeMillis()))

    private companion object { const val COURSES = "courses"; const val CARDS = "cards" }
}

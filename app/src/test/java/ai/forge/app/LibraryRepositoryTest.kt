package ai.forge.app

import ai.forge.app.library.LibraryRepository
import ai.forge.documents.OcrEngine
import ai.forge.documents.PageText
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryRepositoryTest {
    @Test fun importsUserChosenCbzIndexesAndPersists() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val src = File(ctx.cacheDir, "Black Panther 008 (2016).cbz")
        ZipOutputStream(src.outputStream()).use { z ->
            listOf("01.jpg" to "Le roi revient.", "02.jpg" to "Mon costume absorbe l'énergie cinétique.").forEach { (n, t) ->
                z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry()
            }
        }
        val ocr = OcrEngine { PageText(0, String(it), null) }
        val dir = File(ctx.filesDir, "lib-test")
        val repo = LibraryRepository(dir, ocr)
        assertNull(repo.tool())                                   // rien d'indexé : pas d'outil
        repo.indexLocal(ctx.contentResolver, Uri.fromFile(src), src.name)
        val passages = repo.tool()!!.search("costume absorbe énergie")
        assertEquals("Black Panther #8, 2016, p. 2", passages.first().reference)
        assertTrue(!passages.first().verified)

        // Réimport du même fichier : empreinte identique, pas de réindexation ; index rechargé depuis le disque.
        repo.indexLocal(ctx.contentResolver, Uri.fromFile(src), src.name)
        assertEquals(1, LibraryRepository(dir, ocr).index.all().size)
        assertTrue(repo.summary().startsWith("1 fichier(s) indexé(s)"))
    }
}

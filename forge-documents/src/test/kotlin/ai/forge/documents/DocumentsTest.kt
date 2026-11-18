package ai.forge.documents

import ai.forge.comics.ClaimLedger
import ai.forge.comics.EvidenceLevel
import ai.forge.comics.VerificationStatus
import kotlinx.coroutines.test.runTest
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class DocumentsTest {
    private val dir = createTempDirectory("forge-docs").toFile()

    private fun cbz(name: String, entries: Map<String, ByteArray>): File = File(dir, name).also { f ->
        ZipOutputStream(f.outputStream()).use { z -> entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
    }

    /** OCR simulé : le « texte » de chaque image est son contenu. */
    private val fakeOcr = OcrEngine { bytes -> PageText(0, String(bytes), 0.9) }

    @Test fun pagesAreFilteredAndNaturallyOrdered() {
        val f = cbz("bp.cbz", mapOf("p10.jpg" to "dix".toByteArray(), "p2.jpg" to "deux".toByteArray(), "p1.png" to "un".toByteArray(),
            "__MACOSX/._p1.png" to "x".toByteArray(), "notes.txt" to "y".toByteArray()))
        assertEquals(listOf("p1.png", "p2.jpg", "p10.jpg"), SafeArchive().pages(f).map { it.entryName })
    }

    @Test fun zipSlipAndZipBombsAreRefused() {
        assertFailsWith<SafeArchive.UnsafeArchiveException> { SafeArchive().pages(cbz("slip.cbz", mapOf("../../evil.png" to "x".toByteArray()))) }
        val bomb = cbz("bomb.cbz", mapOf("p1.png" to ByteArray(5_000_000)))
        assertFailsWith<SafeArchive.UnsafeArchiveException> { SafeArchive().pages(bomb) }
        assertFailsWith<SafeArchive.UnsafeArchiveException> { SafeArchive(maxEntries = 1).pages(cbz("many.cbz", mapOf("a.png" to byteArrayOf(1), "b.png" to byteArrayOf(2)))) }
    }

    @Test fun scenarioD_findsPageWithReferenceAndStaysUnverifiedUntilChecked() = runTest {
        val f = cbz("Black Panther 008 (2016) (Digital).cbz", mapOf(
            "01.jpg" to "Le roi revient au Wakanda.".toByteArray(),
            "02.jpg" to "Mon costume absorbe l'énergie cinétique de chaque coup.".toByteArray(),
            "03.jpg" to "La reine parle au conseil.".toByteArray()))
        val index = LibraryIndex()
        assertTrue(index.register("drive:abc", f.name, "md5-1"))
        ComicIndexer(SafeArchive(), fakeOcr).indexCbz(index, "drive:abc", f)
        assertEquals(IndexStatus.INDEXED, index.status("drive:abc")!!.status)

        val hit = index.search("costume qui absorbe l'énergie cinétique").first()
        assertEquals(2, hit.page)
        assertEquals("Black Panther", hit.reference.title); assertEquals("8", hit.reference.issue)
        assertEquals(2016, hit.reference.year); assertEquals("drive:abc", hit.reference.sourceFileId)
        assertTrue(hit.excerpt.contains("absorbe"))

        // La piste OCR n'est pas une preuve : le registre la garde non vérifiée et ≤ P2.
        val ledger = ClaimLedger()
        val admitted = ledger.record(hit.draftClaim("c1", "Le costume absorbe l'énergie cinétique", "Black Panther")).claim
        assertEquals(VerificationStatus.UNVERIFIED, admitted.status)
        assertTrue(admitted.level >= EvidenceLevel.P2)
        val shown = ledger.formatResult(admitted)
        assertTrue(shown.contains("p. 2")); assertTrue(shown.contains("Case : non identifiée")); assertTrue(shown.contains("UNVERIFIED"))
        // Après consultation de la page par l'utilisateur : P1 devient possible.
        val checked = ledger.record(admitted.copy(status = VerificationStatus.PRIMARY_CHECKED, level = EvidenceLevel.P1)).claim
        assertEquals(EvidenceLevel.P1, checked.level)
    }

    @Test fun unchangedFilesAreNotReindexedAndStatePersists() = runTest {
        val f = cbz("Thor 001 (1998).cbz", mapOf("1.jpg" to "Mjolnir répond à son appel.".toByteArray()))
        val index = LibraryIndex()
        index.register("t1", f.name, "h1"); ComicIndexer(SafeArchive(), fakeOcr).indexCbz(index, "t1", f)
        assertFalse(index.register("t1", f.name, "h1"))
        val restored = LibraryIndex.fromJson(index.toJson())
        assertEquals(1, restored.search("Mjolnir").size)
        assertTrue(restored.register("t1", f.name, "h2"))           // fichier modifié → réindexation
        assertTrue(restored.search("Mjolnir").isEmpty())
        assertEquals(listOf("t1"), restored.pending().map { it.fileId })
    }

    @Test fun badArchiveIsMarkedFailedNotCrashing() = runTest {
        val index = LibraryIndex()
        val f = cbz("evil.cbz", mapOf("../x.png" to byteArrayOf(1)))
        index.register("e", f.name, "h")
        ComicIndexer(SafeArchive(), fakeOcr).indexCbz(index, "e", f)
        assertEquals(IndexStatus.FAILED, index.status("e")!!.status)
        assertTrue(index.status("e")!!.error!!.contains("Archive refusée"))
    }

    @Test fun userProvidedPageIsSearchableAndVerified() {
        val index = LibraryIndex()
        index.addUserPage("u1", ai.forge.comics.ComicReference("Black Panther", issue = "8", year = 2016, page = 14), "Ce costume absorbe chaque impact.")
        val hit = index.search("costume absorbe impact").single()
        assertTrue(hit.file.userVerified); assertEquals(14, hit.page); assertEquals("8", hit.reference.issue)
    }
}

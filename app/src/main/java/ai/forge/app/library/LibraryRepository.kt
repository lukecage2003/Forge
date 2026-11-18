package ai.forge.app.library

import ai.forge.app.drive.DriveConnector
import ai.forge.documents.ComicIndexer
import ai.forge.documents.IndexStatus
import ai.forge.documents.LibraryIndex
import ai.forge.documents.OcrEngine
import ai.forge.documents.SafeArchive
import ai.forge.reasoning.LibraryPassage
import ai.forge.reasoning.LibraryTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Bibliothèque personnelle : index persistant (stockage privé de l'app, exclu des sauvegardes),
 * alimenté fichier par fichier depuis Drive (téléchargement à la demande, jamais la bibliothèque entière).
 */
class LibraryRepository(private val dir: File, private val ocr: OcrEngine) {
    private val file = File(dir, "library-index.json")
    private val lock = Mutex()
    var index: LibraryIndex = runCatching { LibraryIndex.fromJson(file.readText()) }.getOrDefault(LibraryIndex())
        private set

    suspend fun indexDriveFile(drive: DriveConnector, f: DriveConnector.DriveFile, onProgress: (Int, Int) -> Unit = { _, _ -> }) = lock.withLock {
        if (!index.register(f.id, f.name, f.md5Checksum ?: f.modifiedTime ?: f.size ?: "inconnu")) return@withLock
        val local = drive.download(f)
        when {
            f.name.endsWith(".cbz", true) || f.mimeType.contains("zip") -> ComicIndexer(SafeArchive(), ocr).indexCbz(index, f.id, local, onProgress)
            f.name.endsWith(".pdf", true) || f.mimeType == "application/pdf" -> indexPdf(f.id, local, onProgress)
            else -> index.fail(f.id, "Format non pris en charge : ${f.mimeType}")
        }
        save()
    }

    /**
     * Import depuis le stockage du téléphone (Storage Access Framework) : l'utilisateur choisit
     * les fichiers, Forge n'accède à rien d'autre. Copie dans le cache privé puis indexation.
     */
    suspend fun indexLocal(resolver: android.content.ContentResolver, uri: android.net.Uri, name: String, onProgress: (Int, Int) -> Unit = { _, _ -> }) = lock.withLock {
        val cache = File(dir, "local").apply { mkdirs() }
        val tmp = File(cache, "import.tmp")
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        withContext(Dispatchers.IO) {
            resolver.openInputStream(uri)!!.use { input -> tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024); var n: Int
                while (input.read(buf).also { n = it } >= 0) { digest.update(buf, 0, n); out.write(buf, 0, n) }
            } }
        }
        val fingerprint = digest.digest().joinToString("") { "%02x".format(it) }
        val id = "local:$fingerprint"
        val isPdf = name.endsWith(".pdf", true)
        val target = File(cache, fingerprint + if (isPdf) ".pdf" else ".cbz"); tmp.renameTo(target)
        if (index.register(id, name, fingerprint)) {
            if (isPdf) indexPdf(id, target, onProgress) else ComicIndexer(SafeArchive(), ocr).indexCbz(index, id, target, onProgress)
            save()
        }
    }

    /** Page de comic fournie par l'utilisateur : OCR sur l'appareil, enregistrée comme preuve vérifiée. */
    suspend fun addUserPage(image: ByteArray, reference: ai.forge.comics.ComicReference): String = lock.withLock {
        val text = ocr.recognize(image).text
        val id = "page:" + java.util.UUID.randomUUID()
        index.addUserPage(id, reference, text.ifBlank { "(aucun texte lisible sur la page)" })
        save()
        text
    }

    /** Comics/documents PDF : rendu de chaque page puis OCR sur l'appareil. */
    private suspend fun indexPdf(id: String, file: File, onProgress: (Int, Int) -> Unit) = withContext(Dispatchers.IO) {
        runCatching {
            val texts = mutableListOf<ai.forge.documents.PageText>()
            android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                android.graphics.pdf.PdfRenderer(fd).use { pdf ->
                    for (i in 0 until pdf.pageCount) {
                        onProgress(i + 1, pdf.pageCount)
                        pdf.openPage(i).use { page ->
                            val scale = 1600f / maxOf(page.width, page.height)
                            val bmp = android.graphics.Bitmap.createBitmap((page.width * scale).toInt(), (page.height * scale).toInt(), android.graphics.Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(android.graphics.Color.WHITE)
                            page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val jpg = java.io.ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it); bmp.recycle() }.toByteArray()
                            texts += ocr.recognize(jpg).copy(page = i + 1)
                        }
                    }
                }
            }
            index.complete(id, texts)
        }.onFailure { index.fail(id, "PDF illisible : ${it.message}") }
    }

    /** Page de cours importée : citable avec titre et numéro de page. */
    suspend fun addCoursePage(courseId: String, title: String, page: Int, text: String) = lock.withLock {
        index.addUserPage("$courseId#p$page", ai.forge.comics.ComicReference(title, page = page), text); save()
    }

    suspend fun forget(fileId: String) = lock.withLock { index.forget(fileId); save() }

    fun summary(): String {
        val all = index.all()
        return "${all.count { it.status == IndexStatus.INDEXED }} fichier(s) indexé(s), ${all.count { it.status == IndexStatus.FAILED }} en échec"
    }

    private suspend fun save() = withContext(Dispatchers.IO) {
        dir.mkdirs(); val tmp = File(dir, "library-index.json.tmp"); tmp.writeText(index.toJson()); tmp.renameTo(file)
    }

    /** Outil exposé à l'orchestrateur : null si rien n'est indexé (Forge le dira). */
    fun tool(): LibraryTool? = if (index.all().none { it.status == IndexStatus.INDEXED }) null else LibraryTool { q ->
        index.search(q, limit = 6).map { h -> LibraryPassage(h.reference.display(), h.excerpt, verified = h.file.userVerified) }
    }
}

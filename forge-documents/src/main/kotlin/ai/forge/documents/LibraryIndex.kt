package ai.forge.documents

import ai.forge.comics.Claim
import ai.forge.comics.ComicFileName
import ai.forge.comics.ComicReference
import ai.forge.comics.EvidenceLevel
import ai.forge.comics.EvidenceSource
import ai.forge.comics.SourceTier
import ai.forge.comics.VerificationStatus
import ai.forge.core.text.TextUtils
import ai.forge.memory.Bm25Index
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Texte d'une page (OCR des bulles ou couche texte d'un PDF), avec sa confiance. */
@Serializable
data class PageText(val page: Int, val text: String, val ocrConfidence: Double? = null)

/** Moteur OCR interchangeable (ML Kit sur Android ; Tesseract possible ailleurs). */
fun interface OcrEngine {
    suspend fun recognize(image: ByteArray): PageText
}

@Serializable
enum class IndexStatus { PENDING, INDEXED, FAILED, SKIPPED }

@Serializable
data class IndexedFile(
    val fileId: String,
    val fileName: String,
    /** Empreinte (md5 Drive ou hachage local) : un fichier inchangé n'est jamais réindexé. */
    val fingerprint: String,
    val status: IndexStatus,
    val pageCount: Int = 0,
    val error: String? = null,
    val reference: ComicReference,
    /** Page fournie et identifiée par l'utilisateur lui-même : preuve vérifiée. */
    val userVerified: Boolean = false,
)

@Serializable
private data class Persisted(val files: List<IndexedFile>, val pages: Map<String, List<PageText>>)

/** Candidat trouvé dans la bibliothèque : une PISTE à vérifier, pas une preuve établie. */
data class LibraryHit(val file: IndexedFile, val page: Int, val excerpt: String, val score: Double, val ocrConfidence: Double?) {
    val reference: ComicReference get() = file.reference.copy(page = page)

    /**
     * Brouillon d'affirmation pour le Claim Ledger : statut NON VÉRIFIÉ tant que l'utilisateur
     * n'a pas consulté la page ; le registre plafonne alors automatiquement le niveau à P2.
     */
    fun draftClaim(id: String, statement: String, subject: String) = Claim(
        id = id, statement = statement, subject = subject,
        sources = listOf(EvidenceSource(SourceTier.ORIGINAL_COMIC, reference, description = "Texte reconnu (OCR) : « ${excerpt.take(160)} »")),
        evidenceDescription = "Correspondance OCR dans ${reference.display()} — à confirmer en consultant la page",
        level = EvidenceLevel.P2,
        status = VerificationStatus.UNVERIFIED,
    )
}

/**
 * Index de la bibliothèque personnelle (§7.2, §7.3) : indexation progressive, reprise, empreintes,
 * recherche plein texte par page. Persistable en JSON (stockage local de l'app).
 */
class LibraryIndex {
    private val files = linkedMapOf<String, IndexedFile>()
    private val pages = mutableMapOf<String, List<PageText>>()
    private val bm25 = Bm25Index()
    private val json = Json { ignoreUnknownKeys = true }

    fun status(fileId: String) = files[fileId]
    fun all(): List<IndexedFile> = files.values.toList()
    fun pending(): List<IndexedFile> = files.values.filter { it.status == IndexStatus.PENDING || it.status == IndexStatus.FAILED }

    /** Déclare un fichier ; renvoie vrai s'il faut (ré)indexer (nouveau ou modifié). */
    fun register(fileId: String, fileName: String, fingerprint: String): Boolean {
        val existing = files[fileId]
        if (existing != null && existing.fingerprint == fingerprint && existing.status == IndexStatus.INDEXED) return false
        if (existing != null) removePages(fileId)
        files[fileId] = IndexedFile(fileId, fileName, fingerprint, IndexStatus.PENDING,
            reference = ComicFileName.parse(fileName).copy(sourceFileId = fileId))
        return true
    }

    fun complete(fileId: String, pageTexts: List<PageText>) {
        val f = files[fileId] ?: return
        removePages(fileId)
        pages[fileId] = pageTexts
        pageTexts.forEach { bm25.put("$fileId#${it.page}", it.text) }
        files[fileId] = f.copy(status = IndexStatus.INDEXED, pageCount = pageTexts.size, error = null)
    }

    /** Ajoute une page fournie par l'utilisateur (photo/capture) avec sa référence exacte. */
    fun addUserPage(id: String, reference: ComicReference, text: String) {
        removePages(id)
        files[id] = IndexedFile(id, reference.display(), "user:" + text.hashCode(), IndexStatus.PENDING, reference = reference, userVerified = true)
        complete(id, listOf(PageText(reference.page ?: 1, text, null)))
    }

    fun fail(fileId: String, error: String) { files[fileId]?.let { files[fileId] = it.copy(status = IndexStatus.FAILED, error = error) } }

    fun forget(fileId: String) { removePages(fileId); files.remove(fileId) }

    private fun removePages(fileId: String) { pages.remove(fileId)?.forEach { bm25.remove("$fileId#${it.page}") } }

    fun search(query: String, limit: Int = 10, minOcrConfidence: Double = 0.0): List<LibraryHit> =
        bm25.search(query).mapNotNull { (key, score) ->
            val fileId = key.substringBeforeLast('#'); val page = key.substringAfterLast('#').toInt()
            val f = files[fileId] ?: return@mapNotNull null
            val pt = pages[fileId]?.firstOrNull { it.page == page } ?: return@mapNotNull null
            if ((pt.ocrConfidence ?: 1.0) < minOcrConfidence) return@mapNotNull null
            LibraryHit(f, page, excerptAround(pt.text, query), score, pt.ocrConfidence)
        }.take(limit)

    private fun excerptAround(text: String, query: String, radius: Int = 120): String {
        val stems = TextUtils.stems(query).toSet()
        val folded = TextUtils.fold(text)
        val pos = Regex("[\\p{L}\\p{N}]+").findAll(folded).firstOrNull { TextUtils.stem(it.value) in stems }?.range?.first ?: 0
        val start = (pos - radius).coerceAtLeast(0); val end = (pos + radius).coerceAtMost(text.length)
        return (if (start > 0) "…" else "") + text.substring(start, end).trim() + (if (end < text.length) "…" else "")
    }

    fun toJson(): String = json.encodeToString(Persisted(files.values.toList(), pages))

    companion object {
        fun fromJson(s: String): LibraryIndex = LibraryIndex().apply {
            val p = json.decodeFromString<Persisted>(s)
            p.files.forEach { files[it.fileId] = it }
            p.pages.forEach { (id, list) -> pages[id] = list; list.forEach { bm25.put("$id#${it.page}", it.text) } }
        }
    }
}

/**
 * Indexation progressive d'un CBZ : page par page, interrompable, sans tout télécharger
 * (le fichier est fourni par le cache Drive à la demande).
 */
class ComicIndexer(private val archive: SafeArchive, private val ocr: OcrEngine) {
    suspend fun indexCbz(index: LibraryIndex, fileId: String, file: java.io.File, onProgress: (Int, Int) -> Unit = { _, _ -> }) {
        try {
            val pages = archive.pages(file)
            val texts = pages.map { p ->
                onProgress(p.index, pages.size)
                ocr.recognize(archive.readPage(file, p)).copy(page = p.index)
            }
            index.complete(fileId, texts)
        } catch (e: SafeArchive.UnsafeArchiveException) {
            index.fail(fileId, "Archive refusée : ${e.message}")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            index.fail(fileId, e.message ?: e.toString())
        }
    }
}

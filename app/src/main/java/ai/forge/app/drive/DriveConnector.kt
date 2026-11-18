package ai.forge.app.drive

import ai.forge.comics.ComicFileName
import ai.forge.comics.ComicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

/**
 * Accès Google Drive API v3 (§7). Principes :
 * - portée minimale : `drive.file` par défaut (fichiers choisis par l'utilisateur via le Picker / SAF),
 *   `drive.readonly` uniquement si l'utilisateur active explicitement l'indexation d'un dossier entier ;
 * - jamais de téléchargement massif : métadonnées d'abord, contenu à la demande ;
 * - empreinte md5 pour éviter de retélécharger ou réindexer un fichier inchangé ;
 * - reprise des téléchargements interrompus (en-tête Range).
 *
 * Le jeton OAuth est fourni par [DriveAuth] (Google Identity Services, AuthorizationClient).
 */
class DriveConnector(
    private val accessToken: suspend () -> String,
    private val cacheDir: File,
    private val client: OkHttpClient = OkHttpClient(),
    private val baseUrl: String = "https://www.googleapis.com",
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class DriveFile(
        val id: String,
        val name: String,
        val mimeType: String,
        val size: String? = null,
        val md5Checksum: String? = null,
        val modifiedTime: String? = null,
        val parents: List<String> = emptyList(),
    ) {
        val comicReference: ComicReference get() = ComicFileName.parse(name).copy(sourceFileId = id)
    }

    @Serializable
    private data class FileList(val files: List<DriveFile> = emptyList(), val nextPageToken: String? = null)

    /** Liste les documents indexables d'un dossier (PDF, CBZ, ZIP, images, texte). */
    suspend fun listFolder(folderId: String): List<DriveFile> {
        val q = "'$folderId' in parents and trashed = false and (" +
            INDEXABLE_MIME.joinToString(" or ") { "mimeType = '$it'" } + " or name contains '.cbz')"
        return list(q)
    }

    /** Recherche par nom (ex. « Black Panther »). Les apostrophes sont échappées. */
    suspend fun searchByName(text: String): List<DriveFile> =
        list("name contains '${text.replace("\\", "\\\\").replace("'", "\\'")}' and trashed = false")

    private suspend fun list(q: String): List<DriveFile> = withContext(Dispatchers.IO) {
        val out = mutableListOf<DriveFile>()
        var page: String? = null
        do {
            val url = "$baseUrl/drive/v3/files".toHttpUrl().newBuilder()
                .addQueryParameter("q", q)
                .addQueryParameter("fields", "nextPageToken,files(id,name,mimeType,size,md5Checksum,modifiedTime,parents)")
                .addQueryParameter("pageSize", "200")
                .apply { page?.let { addQueryParameter("pageToken", it) } }
                .build()
            val body = execute(Request.Builder().url(url).header("Authorization", "Bearer ${accessToken()}").build())
            val list = json.decodeFromString<FileList>(body)
            out += list.files
            page = list.nextPageToken
        } while (page != null)
        out
    }

    /**
     * Télécharge (ou reprend) un fichier dans le cache local. Renvoie le fichier local.
     * Si une copie de même empreinte md5 existe déjà, aucun transfert n'a lieu.
     */
    suspend fun download(file: DriveFile): File = withContext(Dispatchers.IO) {
        val target = File(cacheDir, "${file.id}_${file.md5Checksum ?: "nomd5"}")
        val partial = File(target.path + ".part")
        if (target.exists()) return@withContext target
        val offset = if (partial.exists()) partial.length() else 0L
        val req = Request.Builder()
            .url("$baseUrl/drive/v3/files/${file.id}?alt=media")
            .header("Authorization", "Bearer ${accessToken()}")
            .apply { if (offset > 0) header("Range", "bytes=$offset-") }
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw DriveException(resp.code, resp.body?.string().orEmpty().take(300))
            val append = resp.code == 206
            FileOutputStream(partial, append).use { out -> resp.body!!.byteStream().copyTo(out) }
        }
        check(partial.renameTo(target)) { "Impossible de finaliser le téléchargement de ${file.name}" }
        target
    }

    private fun execute(req: Request): String = client.newCall(req).execute().use { resp ->
        val body = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) throw DriveException(resp.code, body.take(300))
        body
    }

    class DriveException(val code: Int, message: String) : Exception("Drive HTTP $code : $message")

    companion object {
        const val SCOPE_FILE = "https://www.googleapis.com/auth/drive.file"
        const val SCOPE_READONLY = "https://www.googleapis.com/auth/drive.readonly"
        val INDEXABLE_MIME = listOf(
            "application/pdf", "application/zip", "application/x-cbz", "application/vnd.comicbook+zip",
            "image/jpeg", "image/png", "image/webp", "text/plain", "text/markdown",
        )
    }
}

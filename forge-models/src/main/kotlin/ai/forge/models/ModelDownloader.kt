package ai.forge.models

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Téléchargement d'un modèle : reprise après coupure (Range), progression, vérification SHA-256,
 * remplacement atomique. Un fichier corrompu n'est jamais utilisé.
 */
class ModelDownloader(
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build(),
) {
    sealed interface Result {
        data class Ready(val file: File) : Result
        data class Failed(val reason: String, val resumable: Boolean) : Result
    }

    suspend fun download(model: DownloadableModel, dir: File, onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> }): Result =
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            val target = File(dir, model.fileName)
            if (target.exists() && target.length() == model.sizeBytes) return@withContext Result.Ready(target)
            if (dir.usableSpace < model.sizeBytes - partialSize(dir, model) + 200_000_000)
                return@withContext Result.Failed("Espace libre insuffisant : ${model.sizeBytes / 1_000_000} Mo nécessaires.", false)
            val part = File(dir, model.fileName + ".part")
            try {
                var offset = if (part.exists()) part.length() else 0L
                if (offset > model.sizeBytes) { part.delete(); offset = 0 }
                if (offset < model.sizeBytes) {
                    val req = Request.Builder().url(model.url).apply { if (offset > 0) header("Range", "bytes=$offset-") }.build()
                    client.newCall(req).execute().use { r ->
                        if (!r.isSuccessful) return@withContext Result.Failed("Serveur : HTTP ${r.code}", true)
                        val append = r.code == 206
                        if (!append) offset = 0
                        FileOutputStream(part, append).use { out ->
                            val input = r.body!!.byteStream()
                            val buf = ByteArray(256 * 1024)
                            var done = offset; var n: Int; var lastReport = 0L
                            while (input.read(buf).also { n = it } >= 0) {
                                coroutineContext.ensureActive()
                                out.write(buf, 0, n); done += n
                                if (done - lastReport > 4_000_000) { onProgress(done, model.sizeBytes); lastReport = done }
                            }
                            onProgress(done, model.sizeBytes)
                        }
                    }
                }
                if (part.length() != model.sizeBytes) return@withContext Result.Failed("Téléchargement incomplet (${part.length()} / ${model.sizeBytes} octets).", true)
                if (sha256(part) != model.sha256.lowercase()) {
                    part.delete()
                    return@withContext Result.Failed("Fichier corrompu (empreinte SHA-256 incorrecte) : supprimé, à retélécharger.", false)
                }
                if (!part.renameTo(target)) return@withContext Result.Failed("Impossible de finaliser le fichier.", true)
                Result.Ready(target)
            } catch (e: java.io.IOException) {
                Result.Failed("Connexion interrompue : le téléchargement reprendra où il s'est arrêté.", true)
            }
        }

    private fun partialSize(dir: File, m: DownloadableModel) = File(dir, m.fileName + ".part").let { if (it.exists()) it.length() else 0L }

    companion object {
        /**
         * Décompression sûre d'un modèle (archive ZIP) : refuse les chemins qui sortent du dossier
         * cible (zip-slip) et les archives anormalement volumineuses.
         */
        fun unzipSafely(zip: File, destDir: File, maxTotalBytes: Long = 2_000_000_000L): File {
            val root = destDir.canonicalFile.apply { mkdirs() }
            var total = 0L
            java.util.zip.ZipInputStream(zip.inputStream().buffered()).use { z ->
                generateSequence { z.nextEntry }.forEach { e ->
                    val out = File(root, e.name).canonicalFile
                    require(out.path == root.path || out.path.startsWith(root.path + File.separator)) { "Chemin interdit dans l'archive : ${e.name}" }
                    if (e.isDirectory) out.mkdirs() else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { o ->
                            val buf = ByteArray(1 shl 16); var n: Int
                            while (z.read(buf).also { n = it } >= 0) { total += n; require(total <= maxTotalBytes) { "Archive trop volumineuse" }; o.write(buf, 0, n) }
                        }
                    }
                }
            }
            return root
        }

        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { i -> val b = ByteArray(1 shl 20); var n: Int; while (i.read(b).also { n = it } >= 0) md.update(b, 0, n) }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

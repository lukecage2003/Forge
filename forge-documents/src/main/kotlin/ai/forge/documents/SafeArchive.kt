package ai.forge.documents

import java.io.File
import java.util.zip.ZipFile

/**
 * Lecture sécurisée des archives de comics (CBZ = ZIP d'images) — §7.2 « archives ZIP lorsque leur
 * traitement est sécurisé ». Protections :
 * - chemins hors archive refusés (zip-slip, chemins absolus, « .. ») ;
 * - nombre d'entrées, taille par image et taille totale plafonnés ;
 * - ratio de compression plafonné (bombe ZIP) ;
 * - seules les images sont retenues ; rien n'est extrait sur disque hors du dossier prévu ;
 * - ordre naturel des pages (« page2 » avant « page10 »).
 */
class SafeArchive(
    private val maxEntries: Int = 2_000,
    private val maxEntryBytes: Long = 40L * 1024 * 1024,
    private val maxTotalBytes: Long = 1_500L * 1024 * 1024,
    private val maxRatio: Double = 100.0,
) {
    data class Page(val index: Int, val entryName: String, val size: Long)

    class UnsafeArchiveException(message: String) : Exception(message)

    private val imageExt = Regex("\\.(jpe?g|png|webp|gif|avif)$", RegexOption.IGNORE_CASE)

    fun pages(file: File): List<Page> = ZipFile(file).use { zip ->
        val entries = zip.entries().toList()
        if (entries.size > maxEntries) throw UnsafeArchiveException("Trop d'entrées (${entries.size}).")
        var total = 0L
        val images = entries.filter { !it.isDirectory }.onEach { e ->
            val n = e.name.replace('\\', '/')
            if (n.startsWith("/") || n.split('/').any { it == ".." } || Regex("^[A-Za-z]:").containsMatchIn(n))
                throw UnsafeArchiveException("Chemin dangereux dans l'archive : ${e.name}")
            if (e.size > maxEntryBytes) throw UnsafeArchiveException("Entrée trop volumineuse : ${e.name}")
            if (e.size > 0 && e.compressedSize > 0 && e.size.toDouble() / e.compressedSize > maxRatio)
                throw UnsafeArchiveException("Taux de compression suspect (bombe ZIP) : ${e.name}")
            total += maxOf(e.size, 0)
            if (total > maxTotalBytes) throw UnsafeArchiveException("Archive trop volumineuse une fois décompressée.")
        }.filter { imageExt.containsMatchIn(it.name) && !it.name.substringAfterLast('/').startsWith(".") && "__MACOSX" !in it.name }
        images.sortedWith { a, b -> naturalCompare(a.name, b.name) }.mapIndexed { i, e -> Page(i + 1, e.name, e.size) }
    }

    /** Lit une page en mémoire, en revérifiant la taille réelle (les en-têtes ZIP peuvent mentir). */
    fun readPage(file: File, page: Page): ByteArray = ZipFile(file).use { zip ->
        val e = zip.getEntry(page.entryName) ?: throw UnsafeArchiveException("Page absente : ${page.entryName}")
        zip.getInputStream(e).use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var read: Int
            while (input.read(buf).also { read = it } >= 0) {
                out.write(buf, 0, read)
                if (out.size() > maxEntryBytes) throw UnsafeArchiveException("Page plus grande qu'annoncé : ${page.entryName}")
            }
            out.toByteArray()
        }
    }

    companion object {
        fun naturalCompare(a: String, b: String): Int {
            val ra = Regex("\\d+|\\D+").findAll(a.lowercase()).map { it.value }.toList()
            val rb = Regex("\\d+|\\D+").findAll(b.lowercase()).map { it.value }.toList()
            for (i in 0 until minOf(ra.size, rb.size)) {
                val x = ra[i]; val y = rb[i]
                val c = if (x[0].isDigit() && y[0].isDigit()) x.toBigInteger().compareTo(y.toBigInteger()) else x.compareTo(y)
                if (c != 0) return c
            }
            return ra.size - rb.size
        }
    }
}

package ai.forge.comics

/**
 * Extraction prudente des métadonnées depuis un nom de fichier de comic
 * (« Black Panther 008 (2016) (Digital).cbz »). Ce qui n'est pas reconnu reste null.
 */
object ComicFileName {
    private val year = Regex("\\((19\\d{2}|20\\d{2})\\)")
    private val issue = Regex("(?:#|\\bv\\d+\\s+|\\s)(\\d{1,4}(?:\\.\\d)?)(?=\\s*(?:\\(|$|of\\b|-))", RegexOption.IGNORE_CASE)
    private val ext = Regex("\\.(cbz|cbr|pdf|zip|cb7)$", RegexOption.IGNORE_CASE)

    fun parse(fileName: String): ComicReference {
        val base = fileName.replace(ext, "").replace('_', ' ').trim()
        val y = year.find(base)?.groupValues?.get(1)?.toInt()
        val iss = issue.find(base)?.groupValues?.get(1)?.trimStart('0')?.ifEmpty { "0" }
        val title = base.substringBefore("(").let { t ->
            val m = issue.find(t)
            if (m != null) t.substring(0, m.range.first) else t
        }.replace(Regex("\\s+v\\d+\\s*$", RegexOption.IGNORE_CASE), "").trim().trimEnd('#', '-').trim()
        return ComicReference(title = title.ifEmpty { base }, issue = iss, year = y, sourceFileName = fileName)
    }
}

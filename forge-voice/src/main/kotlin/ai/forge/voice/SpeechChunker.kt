package ai.forge.voice

/**
 * Transforme un flux de jetons en segments prononçables, pour que la synthèse vocale
 * commence dès la première phrase complète (réponse progressive en streaming, §2.2).
 * Nettoie aussi les artefacts écrits (Markdown, URL) qui n'ont pas de sens à l'oral.
 */
class SpeechChunker(private val minChars: Int = 60, private val maxChars: Int = 320) {
    private val buffer = StringBuilder()

    /** Ajoute des jetons et renvoie les segments prêts à être prononcés. */
    fun push(tokens: String): List<String> {
        buffer.append(tokens)
        val out = mutableListOf<String>()
        while (true) {
            val cut = findCut() ?: break
            val seg = clean(buffer.substring(0, cut))
            buffer.delete(0, cut)
            if (seg.isNotBlank()) out += seg
        }
        return out
    }

    /** Vide le reliquat en fin de génération. */
    fun flush(): String? = clean(buffer.toString()).also { buffer.clear() }.takeIf { it.isNotBlank() }

    fun reset() = buffer.clear()

    private fun findCut(): Int? {
        val s = buffer
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val strong = c == '.' || c == '!' || c == '?' || c == '…' || c == '\n'
            if (strong && i + 1 >= minChars && i + 1 < s.length && (s[i + 1].isWhitespace())) {
                // Évite de couper après une abréviation ou un nombre décimal.
                val prevWord = s.substring(0, i).takeLastWhile { it.isLetterOrDigit() }
                if (c == '.' && prevWord in ABBREVIATIONS) { i++; continue }
                return i + 1
            }
            if (i + 1 >= maxChars && (c == ',' || c == ';' || c == ':') && i + 1 < s.length) return i + 1
            i++
        }
        return null
    }

    companion object {
        private val ABBREVIATIONS = setOf("M", "Mme", "Dr", "p", "cf", "etc", "vol", "n°", "éd")
        private val url = Regex("https?://\\S+")
        private val mdLink = Regex("\\[([^]]+)]\\([^)]+\\)")
        private val mdMarks = Regex("(\\*\\*|__|\\*|`|#+\\s|^>\\s|^[-•]\\s)", RegexOption.MULTILINE)

        private val emoji = Regex("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}\\x{200D}]")
        private val tags = Regex("\\[(B|S|P)?\\d+]|\\[À VÉRIFIER]|\\(non sourcé\\)")
        private val timecode = Regex("\\[\\d{1,2}:\\d{2}]")

        /** Texte prononçable : sans Markdown, URL, émojis, étiquettes de sources ni symboles. */
        fun clean(text: String): String = text
            .replace(mdLink, "$1")
            .replace(url, "le lien")
            .replace(mdMarks, "")
            .replace(emoji, "")
            .replace(tags, "")
            .replace(timecode, "")
            .replace("→", ", ").replace("×", " fois ").replace("&", " et ").replace("%", " pour cent")
            .replace(Regex("[|_#*~^<>{}]"), " ")
            .replace(Regex("\\s+([,.!?…])"), "$1")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}

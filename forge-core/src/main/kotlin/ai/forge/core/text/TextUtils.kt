package ai.forge.core.text

import java.text.Normalizer

/** Utilitaires linguistiques légers, adaptés au français, sans dépendance externe. */
object TextUtils {
    private val diacritics = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val tokenRegex = Regex("[\\p{L}\\p{N}]+")

    /** Mots vides français (liste courte et volontairement conservatrice). */
    val FRENCH_STOPWORDS: Set<String> = setOf(
        "a", "au", "aux", "avec", "ce", "ces", "cet", "cette", "dans", "de", "des", "du", "elle", "en", "et",
        "eux", "il", "ils", "je", "j", "la", "le", "les", "leur", "lui", "ma", "mais", "me", "meme", "mes",
        "moi", "mon", "ne", "nos", "notre", "nous", "on", "ou", "par", "pas", "pour", "qu", "que", "qui",
        "sa", "se", "ses", "son", "sur", "ta", "te", "tes", "toi", "ton", "tu", "un", "une", "vos", "votre",
        "vous", "c", "d", "l", "m", "n", "s", "t", "y", "est", "sont", "etait", "ete", "etre", "avoir",
        "ai", "as", "avons", "avez", "ont", "suis", "es", "sommes", "etes", "ca", "cela", "ceci", "tout",
        "plus", "tres", "bien", "aussi", "comme", "si", "donc", "alors", "the", "of", "and", "to", "in", "is",
    )

    fun fold(text: String): String =
        diacritics.replace(Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD), "")

    fun tokens(text: String): List<String> = tokenRegex.findAll(fold(text)).map { it.value }.toList()

    fun contentTokens(text: String): List<String> =
        tokens(text).filter { it.length > 1 && it !in FRENCH_STOPWORDS }

    /** Racinisation française minimale (suffixes courants) — suffisante pour la recherche lexicale. */
    fun stem(token: String): String {
        if (token.length <= 4) return token
        val suffixes = listOf("issements", "issement", "ations", "ation", "ements", "ement", "ments", "ment",
            "euses", "euse", "eurs", "eur", "ites", "ite", "ives", "ive", "ifs", "if", "ees", "ee", "es", "s", "e", "x")
        for (s in suffixes) {
            if (token.endsWith(s) && token.length - s.length >= 3) return token.dropLast(s.length)
        }
        return token
    }

    fun stems(text: String): List<String> = contentTokens(text).map(::stem)

    fun jaccard(a: Collection<String>, b: Collection<String>): Double {
        val sa = a.toSet(); val sb = b.toSet()
        if (sa.isEmpty() && sb.isEmpty()) return 1.0
        return sa.intersect(sb).size.toDouble() / sa.union(sb).size
    }

    /** Découpe en phrases, en respectant les abréviations courantes. */
    fun sentences(text: String): List<String> {
        val protected = text.replace(Regex("\\b(M|Mme|Dr|p|n°|cf|etc|vol|éd)\\."), "$1\u0000")
        return protected.split(Regex("(?<=[.!?…])\\s+"))
            .map { it.replace('\u0000', '.').trim() }
            .filter { it.isNotEmpty() }
    }

    fun wordCount(text: String): Int = tokenRegex.findAll(text).count()
}

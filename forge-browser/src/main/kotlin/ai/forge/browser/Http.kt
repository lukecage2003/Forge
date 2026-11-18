package ai.forge.browser

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Client HTTP partagé : une seule réserve de connexions (TLS et HTTP/2 réutilisés d'une question
 * à l'autre) et des délais courts — sur téléphone, mieux vaut une source de moins qu'une réponse
 * qui arrive dix secondes plus tard.
 */
object ForgeHttp {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
            .retryOnConnectionFailure(true)
            .build()
    }
}

/**
 * Transforme une phrase parlée en requête de moteur : « Forge, tu peux me chercher qui a créé
 * Black Panther ? » → « créé Black Panther ». Les moteurs wiki exigent presque tous les mots :
 * une phrase entière ne renvoie souvent rien.
 */
object QueryRewriter {
    private val PREFIX = Regex(
        "^(?:(?:ok|hé|hey|dis|salut|bonjour)\\s+)?(?:forge[,!.\\s]+)?" +
            "(?:(?:est-ce que\\s+)?(?:tu\\s+(?:peux|pourrais)|peux-tu|pourrais-tu|est-ce que tu peux)\\s+)?" +
            "(?:me\\s+|m'|nous\\s+)?" +
            "(?:(?:faire|fais|lance|lancer)\\s+(?:une\\s+)?recherche\\s+(?:sur|à propos de|au sujet de)\\s+|" +
            "(?:cherche|chercher|recherche|rechercher|trouve|trouver|dis|dire|explique|expliquer|raconte|raconter|parle|parler|donne|donner)(?:-moi\\s+|\\s+moi\\s+|\\s+)(?:sur\\s+|des infos sur\\s+|des informations sur\\s+)?)?",
        RegexOption.IGNORE_CASE,
    )
    private val STOP = setOf(
        "le", "la", "les", "l", "un", "une", "des", "du", "de", "d", "et", "ou", "à", "a", "au", "aux", "en", "sur", "pour", "par",
        "avec", "dans", "que", "qu", "qui", "quoi", "quel", "quelle", "quels", "quelles", "est", "sont", "c", "ce", "cet", "cette",
        "ces", "il", "elle", "ils", "elles", "on", "je", "j", "tu", "me", "moi", "te", "se", "s", "y", "ne", "pas", "plus", "très",
        "comment", "pourquoi", "quand", "combien", "est-ce", "stp", "svp", "s'il", "plaît", "plait", "vous", "peux", "fait", "faire",
        "the", "of", "is", "what", "who", "how",
    )

    fun keywords(input: String, maxWords: Int = 7): String {
        val stripped = input.trim().replace(PREFIX, "")
        val words = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}'’\\-]*").findAll(stripped).map { it.value.trim('\'', '’', '-') }
            .map { w -> w.substringAfter('\'').substringAfter('’').takeIf { w.length > 2 && w[1] in "'’" } ?: w }
            .filter { it.isNotEmpty() && it.lowercase() !in STOP }
            .toList()
        val result = words.take(maxWords).joinToString(" ")
        return result.ifBlank { input.trim() }
    }
}

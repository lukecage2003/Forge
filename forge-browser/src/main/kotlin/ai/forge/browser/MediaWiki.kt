package ai.forge.browser

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder

/**
 * Recherche sans clé ni compte via l'API publique MediaWiki (Wikipédia, wikis Fandom Marvel/DC).
 * Ce sont des sources communautaires : excellentes pour découvrir, insuffisantes comme preuve
 * (le registre de preuves les plafonne à P4).
 */
class MediaWikiSearchProvider(
    override val id: String,
    override val displayName: String,
    private val host: String,
    private val client: OkHttpClient = ForgeHttp.client,
    private val scheme: String = "https",
) : SearchProvider {
    override val thirdParty = true
    private val json = Json { ignoreUnknownKeys = true }

    /** Wikipédia fournit un résumé en texte brut (TextExtracts) ; les wikis Fandom, le wikitexte. */
    private val wikipedia = host.contains("wikipedia")

    /**
     * Une seule requête : recherche + texte des articles trouvés (generator=search). Plus de
     * téléchargement de page HTML complète (1 à 2 Mo sur Wikipédia) ni de robots.txt : l'API est
     * faite pour l'accès programmatique.
     */
    override suspend fun search(query: String, limit: Int, language: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val base = if (wikipedia) "$scheme://$host/w/api.php" else "$scheme://$host/api.php"
        val b = base.toHttpUrl().newBuilder()
            .addQueryParameter("action", "query").addQueryParameter("generator", "search").addQueryParameter("gsrsearch", query)
            .addQueryParameter("gsrlimit", limit.coerceIn(1, 10).toString()).addQueryParameter("gsrprop", "snippet")
            .addQueryParameter("format", "json").addQueryParameter("formatversion", "2")
        if (wikipedia) b.addQueryParameter("prop", "extracts").addQueryParameter("exintro", "1").addQueryParameter("explaintext", "1")
            .addQueryParameter("exlimit", "max")
        else b.addQueryParameter("prop", "revisions").addQueryParameter("rvprop", "content").addQueryParameter("rvslots", "main")
        val req = Request.Builder().url(b.build()).header("User-Agent", USER_AGENT).header("Accept", "application/json").build()
        client.newCall(req).execute().use { r ->
            check(r.isSuccessful) { "$displayName : HTTP ${r.code}" }
            json.parseToJsonElement(r.body!!.string()).jsonObject["query"]?.jsonObject?.get("pages")?.jsonArray.orEmpty().mapNotNull { e ->
                val o = e.jsonObject
                val title = o["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val index = o["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: Int.MAX_VALUE
                val raw = if (wikipedia) o["extract"]?.jsonPrimitive?.contentOrNull
                else o["revisions"]?.jsonArray?.firstOrNull()?.jsonObject?.get("slots")?.jsonObject?.get("main")?.jsonObject
                    ?.get("content")?.jsonPrimitive?.contentOrNull?.let(WikiText::toPlain)
                val snippet = Jsoup.parse(o["snippet"]?.jsonPrimitive?.contentOrNull.orEmpty()).text()
                index to SearchResult("$scheme://$host/wiki/" + URLEncoder.encode(title.replace(' ', '_'), "UTF-8").replace("%2F", "/"),
                    title, snippet.ifBlank { raw.orEmpty().take(200) }, raw?.trim()?.takeIf { it.length >= 80 })
            }.sortedBy { it.first }.map { it.second }
        }
    }

    companion object {
        // En-têtes HTTP : ASCII uniquement (un caractère accentué fait échouer la requête).
        const val USER_AGENT = "ForgeAssistant/0.4 (personal assistant for Android; low volume)"
        fun wikipediaFr(client: OkHttpClient = ForgeHttp.client) = MediaWikiSearchProvider("wikipedia-fr", "Wikipédia (fr)", "fr.wikipedia.org", client)
        fun marvelWiki(client: OkHttpClient = ForgeHttp.client) = MediaWikiSearchProvider("marvel-fandom", "Marvel Database (Fandom)", "marvel.fandom.com", client)
        fun dcWiki(client: OkHttpClient = ForgeHttp.client) = MediaWikiSearchProvider("dc-fandom", "DC Database (Fandom)", "dc.fandom.com", client)
    }
}

/**
 * Combine plusieurs fournisseurs : interroge en parallèle, entrelace les résultats (diversité des
 * sources), ignore un fournisseur en panne au lieu d'échouer. Les wikis comics ne sont consultés
 * que pour les sujets comics, pour ne pas polluer les recherches générales.
 */
class CompositeSearchProvider(
    private val general: List<SearchProvider>,
    private val comics: List<SearchProvider> = emptyList(),
    private val isComicsTopic: (String) -> Boolean = { q -> COMICS.containsMatchIn(q.lowercase()) },
) : SearchProvider {
    override val id = "composite"
    override val displayName = (general + comics).joinToString(" + ") { it.displayName }
    override val thirdParty = (general + comics).any { it.thirdParty }

    override suspend fun search(query: String, limit: Int, language: String): List<SearchResult> = coroutineScope {
        val active = if (isComicsTopic(query)) comics + general else general
        val lists = active.map { p -> async { runCatching { p.search(query, limit, language) }.getOrDefault(emptyList()) } }.awaitAll()
        val merged = mutableListOf<SearchResult>()
        var i = 0
        while (merged.size < limit && lists.any { it.size > i }) { lists.forEach { l -> if (l.size > i && merged.size < limit) merged += l[i] }; i++ }
        merged.distinctBy { it.url }
    }

    companion object {
        val COMICS = Regex("comic|marvel|\\bdc\\b|vibranium|wakanda|panther|superman|batman|spider|avengers|x-men|thor|hulk|iron man|joker|flash|wonder woman|galactus|thanos|venom|wolverine|daredevil|green lantern|justice league|mutant")
    }
}

/** Wikitexte → texte lisible (modèles de fiche « | Clé = valeur » gardés, références retirées). */
object WikiText {
    fun toPlain(src: String): String {
        var t = src
        t = t.replace(Regex("<ref[^>/]*/>"), "").replace(Regex("(?s)<ref[^>]*>.*?</ref>"), "").replace(Regex("(?s)<!--.*?-->"), "")
        // Références et petits modèles en ligne : {{r|...}}, {{citation|...}}…
        t = t.replace(Regex("\\{\\{(?:r|ref|cite|citation|sic|fact|cn)\\|[^{}]*\\}\\}", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("\\[\\[(?:File|Fichier|Image|Category|Catégorie):[^\\]]*\\]\\]", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("\\[\\[[^\\]|]*\\|([^\\]]*)\\]\\]"), "$1").replace(Regex("\\[\\[([^\\]]*)\\]\\]"), "$1")
        t = t.replace(Regex("\\[https?://\\S+ ([^\\]]*)\\]"), "$1").replace(Regex("'{2,}"), "")
        val lines = t.lines().mapNotNull { line ->
            val l = line.trim()
            when {
                l.startsWith("|") -> {
                    val key = l.removePrefix("|").substringBefore('=').trim()
                    val value = l.substringAfter('=', "").trim().replace(Regex("\\{\\{[^{}]*\\}\\}"), "").replace(Regex("<br\\s*/?>"), ", ").trim()
                    if (value.isEmpty() || key.isEmpty() || key.contains("Image", true) || key.contains("Gallery", true)) null else "$key : $value"
                }
                l.startsWith("{{") || l.startsWith("}}") || l.startsWith("__") -> null
                l.startsWith("=") -> l.trim('=', ' ').takeIf { it.isNotEmpty() }?.let { "\n$it" }
                else -> l.replace(Regex("\\{\\{[^{}]*\\}\\}"), "").replace(Regex("<[^>]+>"), "").trim().ifEmpty { null }
            }
        }
        return lines.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
    }
}

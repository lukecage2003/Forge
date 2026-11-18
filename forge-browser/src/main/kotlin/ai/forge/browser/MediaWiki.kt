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
    private val client: OkHttpClient = OkHttpClient(),
    private val scheme: String = "https",
) : SearchProvider {
    override val thirdParty = true
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun search(query: String, limit: Int, language: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val url = "$scheme://$host/api.php".let { if (host.contains("wikipedia")) "$scheme://$host/w/api.php" else it }.toHttpUrl().newBuilder()
            .addQueryParameter("action", "query").addQueryParameter("list", "search").addQueryParameter("srsearch", query)
            .addQueryParameter("srlimit", limit.toString()).addQueryParameter("format", "json").build()
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).header("Accept", "application/json").build()
        client.newCall(req).execute().use { r ->
            check(r.isSuccessful) { "$displayName : HTTP ${r.code}" }
            json.parseToJsonElement(r.body!!.string()).jsonObject["query"]?.jsonObject?.get("search")?.jsonArray.orEmpty().mapNotNull { e ->
                val o = e.jsonObject
                val title = o["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val snippet = Jsoup.parse(o["snippet"]?.jsonPrimitive?.contentOrNull.orEmpty()).text()
                SearchResult("$scheme://$host/wiki/" + URLEncoder.encode(title.replace(' ', '_'), "UTF-8").replace("%2F", "/"), title, snippet)
            }
        }
    }

    companion object {
        // En-têtes HTTP : ASCII uniquement (un caractère accentué fait échouer la requête).
        const val USER_AGENT = "ForgeAssistant/0.4 (personal assistant for Android; low volume)"
        fun wikipediaFr(client: OkHttpClient = OkHttpClient()) = MediaWikiSearchProvider("wikipedia-fr", "Wikipédia (fr)", "fr.wikipedia.org", client)
        fun marvelWiki(client: OkHttpClient = OkHttpClient()) = MediaWikiSearchProvider("marvel-fandom", "Marvel Database (Fandom)", "marvel.fandom.com", client)
        fun dcWiki(client: OkHttpClient = OkHttpClient()) = MediaWikiSearchProvider("dc-fandom", "DC Database (Fandom)", "dc.fandom.com", client)
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

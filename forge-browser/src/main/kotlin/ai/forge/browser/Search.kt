package ai.forge.browser

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

data class SearchResult(val url: String, val title: String, val snippet: String)

/**
 * Fournisseur de recherche Web interchangeable (§6, §13.3). La requête quitte l'appareil :
 * un SearXNG auto-hébergé la garde dans le réseau de l'utilisateur ; une API tierce la transmet
 * au fournisseur choisi. Forge l'indique dans les réglages.
 */
interface SearchProvider {
    val id: String
    val displayName: String
    /** Vrai si la requête est envoyée à un tiers (et non à un service de l'utilisateur). */
    val thirdParty: Boolean
    suspend fun search(query: String, limit: Int = 8, language: String = "fr"): List<SearchResult>
}

/** SearXNG (méta-moteur libre, auto-hébergeable) — API JSON : /search?format=json. */
class SearxngSearchProvider(
    private val baseUrl: String,
    private val client: OkHttpClient = OkHttpClient(),
    override val thirdParty: Boolean = false,
) : SearchProvider {
    override val id = "searxng"
    override val displayName = "SearXNG ($baseUrl)"
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun search(query: String, limit: Int, language: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val url = "${baseUrl.trimEnd('/')}/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query).addQueryParameter("format", "json").addQueryParameter("language", language).build()
        client.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { r ->
            check(r.isSuccessful) { "SearXNG HTTP ${r.code}" }
            json.parseToJsonElement(r.body!!.string()).jsonObject["results"]?.jsonArray.orEmpty().mapNotNull { e ->
                val o = e.jsonObject
                val u = o["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                SearchResult(u, o["title"]?.jsonPrimitive?.contentOrNull.orEmpty(), o["content"]?.jsonPrimitive?.contentOrNull.orEmpty())
            }.take(limit)
        }
    }
}

/** Brave Search API (clé fournie par l'utilisateur). */
class BraveSearchProvider(
    private val apiKey: String,
    private val client: OkHttpClient = OkHttpClient(),
    private val baseUrl: String = "https://api.search.brave.com",
) : SearchProvider {
    override val id = "brave"
    override val displayName = "Brave Search (API, tiers)"
    override val thirdParty = true
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun search(query: String, limit: Int, language: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/res/v1/web/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query).addQueryParameter("count", limit.toString()).addQueryParameter("search_lang", language).build()
        client.newCall(Request.Builder().url(url).header("X-Subscription-Token", apiKey).header("Accept", "application/json").build()).execute().use { r ->
            check(r.isSuccessful) { "Brave HTTP ${r.code}" }
            json.parseToJsonElement(r.body!!.string()).jsonObject["web"]?.jsonObject?.get("results")?.jsonArray.orEmpty().mapNotNull { e ->
                val o = e.jsonObject
                val u = o["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                SearchResult(u, o["title"]?.jsonPrimitive?.contentOrNull.orEmpty(), o["description"]?.jsonPrimitive?.contentOrNull.orEmpty())
            }
        }
    }
}

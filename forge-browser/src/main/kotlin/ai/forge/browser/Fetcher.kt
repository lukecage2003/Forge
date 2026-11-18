package ai.forge.browser

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

data class FetchedPage(
    val url: String,
    val title: String,
    val text: String,
    val publishedIso: String?,
    val truncated: Boolean,
)

sealed interface FetchResult {
    data class Ok(val page: FetchedPage) : FetchResult
    data class Refused(val reason: String) : FetchResult
    data class Failed(val reason: String) : FetchResult
}

/**
 * Récupération de pages respectueuse (§6 « respecter les protections des sites ») :
 * - robots.txt respecté (règles du groupe « * » et du nôtre) ;
 * - pas de contournement de paywall, captcha ou authentification ;
 * - taille plafonnée, types HTML/texte uniquement, schémas http(s) et hôtes publics uniquement.
 */
class PageFetcher(
    private val client: OkHttpClient = ForgeHttp.client,
    private val maxBytes: Long = 800_000,
    private val userAgent: String = "ForgeAssistant/0.2 (assistant personnel; respecte robots.txt)",
    private val allowPrivateHosts: Boolean = false,
) {
    private val robotsCache = java.util.concurrent.ConcurrentHashMap<String, RobotsRules>()

    suspend fun fetch(rawUrl: String): FetchResult = withContext(Dispatchers.IO) {
        val url = rawUrl.toHttpUrlOrNull() ?: return@withContext FetchResult.Refused("URL invalide")
        if (url.scheme !in setOf("http", "https")) return@withContext FetchResult.Refused("Schéma non autorisé")
        if (!allowPrivateHosts && isPrivateHost(url.host)) return@withContext FetchResult.Refused("Hôte local ou privé refusé")
        if (!robots(url).allows(url.encodedPath)) return@withContext FetchResult.Refused("Interdit par robots.txt")
        runCatching {
            client.newCall(Request.Builder().url(url).header("User-Agent", userAgent).header("Accept", "text/html,text/plain").build()).execute().use { r ->
                when {
                    r.code == 401 || r.code == 402 || r.code == 403 -> return@withContext FetchResult.Refused("Accès protégé (HTTP ${r.code}) : non contourné")
                    !r.isSuccessful -> return@withContext FetchResult.Failed("HTTP ${r.code}")
                }
                val type = r.header("Content-Type").orEmpty()
                if (!type.contains("html") && !type.startsWith("text/")) return@withContext FetchResult.Refused("Type non pris en charge : $type")
                val source = r.body!!.source()
                source.request(maxBytes + 1)
                val truncated = source.buffer.size > maxBytes
                val bytes = source.buffer.readByteArray(minOf(source.buffer.size, maxBytes))
                val body = String(bytes, r.body!!.contentType()?.charset() ?: Charsets.UTF_8)
                val page = if (type.contains("html")) ReadableExtractor.extract(body, url.toString()) else
                    FetchedPage(url.toString(), url.toString(), body, null, false)
                FetchResult.Ok(page.copy(truncated = truncated))
            }
        }.getOrElse { FetchResult.Failed(it.message ?: it.toString()) }
    }

    private fun robots(url: HttpUrl): RobotsRules = robotsCache.getOrPut(url.scheme + "://" + url.host + ":" + url.port) {
        val robotsUrl = url.newBuilder().encodedPath("/robots.txt").query(null).build()
        runCatching {
            client.newCall(Request.Builder().url(robotsUrl).header("User-Agent", userAgent).build()).execute().use { r ->
                when {
                    r.isSuccessful -> RobotsRules.parse(r.body!!.string(), "ForgeAssistant")
                    r.code in 400..499 -> RobotsRules.ALLOW_ALL   // pas de robots.txt : accès autorisé
                    else -> RobotsRules.DENY_ALL                  // serveur en erreur : prudence
                }
            }
        }.getOrDefault(RobotsRules.DENY_ALL)
    }

    private fun isPrivateHost(host: String): Boolean {
        val h = host.lowercase()
        if (h == "localhost" || h.endsWith(".local") || h.endsWith(".internal")) return true
        val ip = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)$").find(h)?.groupValues?.drop(1)?.map { it.toInt() } ?: return false
        return ip[0] == 10 || ip[0] == 127 || (ip[0] == 192 && ip[1] == 168) || (ip[0] == 172 && ip[1] in 16..31) || (ip[0] == 169 && ip[1] == 254) || ip[0] == 0
    }
}

/** Sous-ensemble standard de robots.txt (RFC 9309) : groupes, Allow/Disallow, règle la plus longue. */
class RobotsRules(private val rules: List<Pair<Boolean, String>>) {
    fun allows(path: String): Boolean {
        val p = path.ifEmpty { "/" }
        val match = rules.filter { (_, prefix) -> prefix.isNotEmpty() && matches(p, prefix) }.maxByOrNull { it.second.length } ?: return true
        return match.first
    }

    private fun matches(path: String, pattern: String): Boolean {
        val anchored = pattern.endsWith("$")
        val body = pattern.removeSuffix("$").split("*").joinToString(".*") { Regex.escape(it) }
        return Regex("^" + body + if (anchored) "$" else "").containsMatchIn(path)
    }

    companion object {
        val ALLOW_ALL = RobotsRules(emptyList())
        val DENY_ALL = RobotsRules(listOf(false to "/"))

        fun parse(text: String, agent: String): RobotsRules {
            val groups = mutableListOf<Pair<MutableList<String>, MutableList<Pair<Boolean, String>>>>()
            var lastWasAgent = false
            text.lineSequence().map { it.substringBefore('#').trim() }.filter { it.contains(':') }.forEach { line ->
                val key = line.substringBefore(':').trim().lowercase(); val value = line.substringAfter(':').trim()
                when (key) {
                    "user-agent" -> {
                        if (!lastWasAgent || groups.isEmpty()) groups += mutableListOf<String>() to mutableListOf()
                        groups.last().first += value.lowercase(); lastWasAgent = true
                    }
                    "allow", "disallow" -> {
                        if (groups.isNotEmpty()) groups.last().second += (key == "allow") to value
                        lastWasAgent = false
                    }
                    else -> lastWasAgent = false
                }
            }
            val mine = groups.filter { g -> g.first.any { it != "*" && agent.lowercase().contains(it) } }
            val chosen = mine.ifEmpty { groups.filter { "*" in it.first } }
            return RobotsRules(chosen.flatMap { it.second })
        }
    }
}

/** Extraction du contenu lisible d'une page HTML (titre, texte principal, date de publication). */
object ReadableExtractor {
    fun extract(html: String, url: String): FetchedPage {
        val doc = Jsoup.parse(html, url)
        val published = listOf("meta[property=article:published_time]", "meta[name=date]", "meta[itemprop=datePublished]", "time[datetime]")
            .firstNotNullOfOrNull { sel -> doc.selectFirst(sel)?.let { it.attr("content").ifEmpty { it.attr("datetime") }.ifEmpty { null } } }
        doc.select("script, style, noscript, nav, header, footer, aside, form, iframe, svg, [aria-hidden=true], .cookie, .advert, .ads").remove()
        // Bloc principal : <article>/<main> s'ils existent, sinon le conteneur le plus riche en paragraphes.
        val main = doc.selectFirst("article") ?: doc.selectFirst("main")
            ?: doc.select("div, section").maxByOrNull { el -> el.select("> p").sumOf { it.text().length } }
            ?: doc.body()
        val paragraphs = main.select("h1, h2, h3, p, li, blockquote").map { it.text().trim() }.filter { it.length > 1 }
        val text = (if (paragraphs.isNotEmpty()) paragraphs.joinToString("\n\n") else main.text()).trim()
        return FetchedPage(url, doc.title().ifBlank { url }, text, published, false)
    }
}

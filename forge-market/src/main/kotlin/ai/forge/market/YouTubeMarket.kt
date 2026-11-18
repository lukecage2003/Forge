package ai.forge.market

import ai.forge.core.text.TextUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Duration
import java.time.Instant
import kotlin.math.max

data class VideoStat(
    val id: String,
    val title: String,
    val channelId: String,
    val channelTitle: String,
    val publishedAt: Instant,
    val views: Long,
    val likes: Long?,
    val comments: Long?,
    val durationSeconds: Int,
    val channelSubscribers: Long?,
) {
    val isShort: Boolean get() = durationSeconds in 1..180
    /** Vues rapportées à la taille de la chaîne : > 1 = la vidéo a dépassé l'audience habituelle. */
    val outlierScore: Double get() = views.toDouble() / max(channelSubscribers ?: 0L, 1_000L)
    fun viewsPerDay(now: Instant): Double = views / max(1.0, Duration.between(publishedAt, now).toHours() / 24.0)
}

/**
 * Client minimal de l'API officielle YouTube Data v3 (clé gratuite fournie par l'utilisateur).
 * Coût par analyse : search.list (100 unités) + videos.list (1) + channels.list (1) ≈ 102 unités
 * sur un quota quotidien gratuit de 10 000.
 */
class YouTubeDataClient(
    private val apiKey: String,
    private val client: OkHttpClient = OkHttpClient(),
    private val baseUrl: String = "https://www.googleapis.com/youtube/v3",
) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun get(path: String, params: Map<String, String>): JsonObject {
        val url = "$baseUrl/$path".toHttpUrl().newBuilder().apply { params.forEach { (k, v) -> addQueryParameter(k, v) }; addQueryParameter("key", apiKey) }.build()
        client.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val reason = runCatching { json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull }.getOrNull()
                throw IllegalStateException("YouTube API HTTP ${r.code}${reason?.let { " : $it" } ?: ""}")
            }
            return json.parseToJsonElement(body).jsonObject
        }
    }

    suspend fun topVideos(query: String, publishedAfter: Instant, max: Int = 25, language: String = "fr"): List<VideoStat> = withContext(Dispatchers.IO) {
        val ids = get("search", mapOf("part" to "snippet", "q" to query, "type" to "video", "maxResults" to max.toString(),
            "order" to "viewCount", "publishedAfter" to publishedAfter.toString(), "relevanceLanguage" to language))["items"]?.jsonArray.orEmpty()
            .mapNotNull { it.jsonObject["id"]?.jsonObject?.get("videoId")?.jsonPrimitive?.contentOrNull }
        if (ids.isEmpty()) return@withContext emptyList()
        val videos = get("videos", mapOf("part" to "snippet,statistics,contentDetails", "id" to ids.joinToString(",")))["items"]?.jsonArray.orEmpty().map { it.jsonObject }
        val channelIds = videos.mapNotNull { it["snippet"]?.jsonObject?.get("channelId")?.jsonPrimitive?.contentOrNull }.distinct()
        val subs = if (channelIds.isEmpty()) emptyMap() else get("channels", mapOf("part" to "statistics", "id" to channelIds.joinToString(",")))["items"]?.jsonArray.orEmpty()
            .associate { c -> c.jsonObject["id"]!!.jsonPrimitive.content to c.jsonObject["statistics"]?.jsonObject?.get("subscriberCount")?.jsonPrimitive?.contentOrNull?.toLongOrNull() }
        videos.mapNotNull { v ->
            val sn = v["snippet"]?.jsonObject ?: return@mapNotNull null
            val st = v["statistics"]?.jsonObject
            val ch = sn["channelId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            VideoStat(
                id = v["id"]!!.jsonPrimitive.content,
                title = sn["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                channelId = ch, channelTitle = sn["channelTitle"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                publishedAt = Instant.parse(sn["publishedAt"]!!.jsonPrimitive.content),
                views = st?.get("viewCount")?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0,
                likes = st?.get("likeCount")?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
                comments = st?.get("commentCount")?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
                durationSeconds = parseIsoDuration(v["contentDetails"]?.jsonObject?.get("duration")?.jsonPrimitive?.contentOrNull.orEmpty()),
                channelSubscribers = subs[ch],
            )
        }
    }

    companion object {
        fun parseIsoDuration(s: String): Int {
            val m = Regex("P(?:(\\d+)D)?T?(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?").matchEntire(s) ?: return 0
            val (d, h, mi, se) = m.destructured
            return (d.toIntOrNull() ?: 0) * 86_400 + (h.toIntOrNull() ?: 0) * 3_600 + (mi.toIntOrNull() ?: 0) * 60 + (se.toIntOrNull() ?: 0)
        }
    }
}

/** Résultat d'analyse : chiffres réels, échantillon indiqué, aucune extrapolation présentée comme certaine. */
data class MarketReport(
    val query: String,
    val sampleSize: Int,
    val topOutliers: List<VideoStat>,
    val insights: List<String>,
) {
    fun summary(): String = buildString {
        appendLine("Analyse de $sampleSize vidéos YouTube récentes sur « $query » (données de l'API officielle) :")
        topOutliers.take(5).forEach { appendLine("- « ${it.title} » — ${fmt(it.views)} vues, ×${"%.1f".format(it.outlierScore)} la taille de sa chaîne (${it.channelTitle})") }
        insights.forEach { appendLine("- $it") }
        if (sampleSize < 15) appendLine("- Échantillon réduit : tendances indicatives seulement.")
    }.trim()

    private fun fmt(n: Long) = when { n >= 1_000_000 -> "%.1f M".format(n / 1e6); n >= 1_000 -> "%.0f k".format(n / 1e3); else -> n.toString() }
}

/** Analyse des vidéos qui surperforment : formats, durées, titres. */
object MarketAnalyzer {
    private val emotional = Regex("(incroyable|secret|jamais|personne|vraiment|pourquoi|comment|fou|choc|verite|cache|explique|meilleur|pire|top|enfin)")

    fun analyze(query: String, videos: List<VideoStat>, now: Instant = Instant.now()): MarketReport {
        if (videos.isEmpty()) return MarketReport(query, 0, emptyList(), listOf("Aucune vidéo trouvée : sujet de niche ou formulation à élargir."))
        val ranked = videos.sortedByDescending { it.outlierScore }
        val top = ranked.take(max(3, videos.size / 4)); val rest = ranked.drop(top.size)
        val insights = mutableListOf<String>()

        fun median(xs: List<Double>) = xs.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
        fun compare(label: String, pred: (VideoStat) -> Boolean) {
            val withF = videos.filter(pred); val without = videos.filterNot(pred)
            if (withF.size < 3 || without.size < 3) return
            val ratio = median(withF.map { it.outlierScore }) / max(median(without.map { it.outlierScore }), 1e-6)
            if (ratio >= 1.3) insights += "$label : ×${"%.1f".format(ratio)} de performance médiane (${withF.size} vidéos)"
            else if (ratio <= 0.77) insights += "$label : sous-performent (×${"%.1f".format(ratio)}, ${withF.size} vidéos)"
        }
        compare("Shorts (≤ 3 min)") { it.isShort }
        compare("Titres en question") { "?" in it.title }
        compare("Titres avec un chiffre") { Regex("\\d").containsMatchIn(it.title) }
        compare("Titres « contre / vs »") { Regex("(?i)\\b(vs|contre|versus)\\b").containsMatchIn(it.title) }
        compare("Titres à forte curiosité") { emotional.containsMatchIn(TextUtils.fold(it.title)) }

        val longTop = top.filter { !it.isShort }.map { it.durationSeconds }
        if (longTop.size >= 2) insights += "Durée médiane des vidéos longues qui surperforment : ${longTop.sorted()[longTop.size / 2] / 60} min"
        val words = top.flatMap { TextUtils.contentTokens(it.title).filter { w -> w.length > 3 } }.groupingBy { it }.eachCount()
            .filter { it.value >= 2 }.entries.sortedByDescending { it.value }.take(6).map { it.key }
        if (words.isNotEmpty()) insights += "Mots récurrents dans les titres gagnants : ${words.joinToString(", ")}"
        val avgTitle = top.map { it.title.length }.average().toInt()
        insights += "Longueur moyenne des titres gagnants : $avgTitle caractères"
        val fresh = top.count { Duration.between(it.publishedAt, now).toDays() <= 30 }
        if (fresh > 0) insights += "$fresh vidéo(s) gagnante(s) publiée(s) ces 30 derniers jours : sujet actuellement porteur"
        if (rest.isNotEmpty() && top.isNotEmpty()) {
            val eng = { v: VideoStat -> ((v.likes ?: 0) + 3 * (v.comments ?: 0)).toDouble() / max(v.views, 1) }
            val r = median(top.map(eng)) / max(median(rest.map(eng)), 1e-9)
            if (r >= 1.3) insights += "Les gagnantes déclenchent ×${"%.1f".format(r)} plus d'engagement (likes, commentaires) : susciter le débat paie"
        }
        return MarketReport(query, videos.size, top, insights)
    }
}

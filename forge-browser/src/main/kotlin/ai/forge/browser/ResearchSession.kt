package ai.forge.browser

import ai.forge.research.Citation
import ai.forge.research.Corroboration
import ai.forge.research.SourceClassifier
import ai.forge.research.SourceType
import ai.forge.security.UntrustedContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate

/** Source consultée et prête à être citée. */
data class ResearchSource(val index: Int, val citation: Citation, val excerpt: String, val suspicious: Boolean)

data class ResearchDossier(
    val query: String,
    val sources: List<ResearchSource>,
    val refused: List<Pair<String, String>>,
    val assessment: Corroboration.Assessment,
    /** Contexte prêt à injecter dans le prompt : chaque source encadrée comme donnée non fiable. */
    val promptContext: String,
) {
    fun bibliography(): String = sources.joinToString("\n") {
        "[${it.index}] ${it.citation.title} — ${it.citation.type.label} — ${it.citation.url} (consulté le ${it.citation.accessedAtIso})"
    }
}

/**
 * Recherche documentaire (§6, scénario C) : rechercher → consulter → classer → encadrer → citer.
 * Les pages sont des DONNÉES ; leur contenu ne peut pas modifier les règles de Forge.
 */
class ResearchSession(
    private val search: SearchProvider,
    private val fetcher: PageFetcher,
    private val classifier: SourceClassifier = SourceClassifier(),
    private val today: () -> LocalDate = LocalDate::now,
    private val history: MutableList<String> = mutableListOf(),
    /** Temps maximal accordé aux pages à télécharger : au-delà, on répond avec ce qu'on a. */
    private val fetchBudgetMs: Long = 4_000,
    private val cacheTtlMs: Long = 20 * 60_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val searchHistory: List<String> get() = history
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, ResearchDossier>>()
    /**
     * Téléchargements détachés de la réponse : une page trop lente est abandonnée sans que la
     * réponse attende la fin de sa connexion (un appel réseau bloquant ne s'interrompt pas tout seul).
     */
    private val fetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun run(query: String, maxSources: Int = 5, excerptChars: Int = 2_500, recentTopic: Boolean = false, requiresPrimary: Boolean = false): ResearchDossier = coroutineScope {
        history += query
        val keywords = QueryRewriter.keywords(query)
        val key = "$keywords|$maxSources|$excerptChars|$recentTopic|$requiresPrimary".lowercase()
        cache[key]?.let { (at, d) -> if (clock() - at < cacheTtlMs) return@coroutineScope d.copy(query = query) }
        val results = search.search(keywords, limit = maxSources + 2)
            .distinctBy { it.url.substringBefore('#') }
        // Les wikis renvoient déjà le texte : aucune page à télécharger. Les autres pages sont
        // récupérées en parallèle, dans un budget de temps ; une page trop lente est ignorée.
        val pending = results.filter { it.content == null }.associateWith { r -> fetchScope.async { fetcher.fetch(r.url) } }
        val deadline = System.nanoTime() + fetchBudgetMs * 1_000_000
        val fetched = results.map { r ->
            val direct = r.content?.let { FetchResult.Ok(FetchedPage(r.url, r.title, it, null, false)) }
            r to (direct ?: pending.getValue(r).let { job ->
                val left = (deadline - System.nanoTime()) / 1_000_000
                (if (job.isCompleted) job.await() else if (left > 0) withTimeoutOrNull(left) { job.await() } else null)
                    ?: FetchResult.Failed("trop lente (plus de ${fetchBudgetMs / 1000} s)").also { job.cancel() }
            })
        }
        val refused = mutableListOf<Pair<String, String>>()
        val sources = mutableListOf<ResearchSource>()
        for ((r, f) in fetched) {
            if (sources.size >= maxSources) break
            when (f) {
                is FetchResult.Ok -> {
                    val type = classifier.classify(f.page.url)
                    val excerpt = f.page.text.take(excerptChars)
                    val wrapped = UntrustedContent.wrap(f.page.url, excerpt)
                    sources += ResearchSource(sources.size + 1,
                        Citation(f.page.url, f.page.title.ifBlank { r.title }, type, excerpt.take(300), today().toString(), f.page.publishedIso),
                        wrapped.text, wrapped.suspicious)
                }
                is FetchResult.Refused -> refused += r.url to f.reason
                is FetchResult.Failed -> refused += r.url to f.reason
            }
        }
        val assessment = Corroboration.assess(sources.map { it.citation }, requiresPrimary, recentTopic)
        val context = buildString {
            appendLine(UntrustedContent.SYSTEM_RULE)
            appendLine("Sources consultées (cite-les par leur numéro [n] ; signale leurs désaccords ; distingue faits, hypothèses et opinions) :")
            sources.forEach { appendLine("[${it.index}] ${it.citation.type.label} — ${it.citation.title}\n${it.excerpt}") }
            appendLine("Évaluation : ${assessment.note}")
            if (sources.none { it.citation.type == SourceType.PRIMARY || it.citation.type == SourceType.INSTITUTIONAL })
                appendLine("Aucune source primaire ou institutionnelle : ne présente pas la recherche comme une vérification définitive.")
        }
        ResearchDossier(query, sources, refused, assessment, context).also { if (sources.isNotEmpty()) cache[key] = clock() to it }
    }
}

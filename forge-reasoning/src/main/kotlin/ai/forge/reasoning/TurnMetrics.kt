package ai.forge.reasoning

/** Mesures réelles d'un tour (affichées dans le diagnostic : où passe le temps sur le téléphone). */
data class TurnMetrics(
    val voice: Boolean,
    val engine: String?,
    val specialist: String?,
    /** Temps de la recherche Web, si elle a eu lieu. */
    val researchMs: Long?,
    /** Délai entre la question et le premier mot affiché/prononcé. */
    val firstTokenMs: Long?,
    val totalMs: Long,
    val chars: Int,
    val failed: Boolean,
) {
    /** Vitesse d'écriture du modèle, en mots par seconde (hors attente du premier mot). */
    val wordsPerSecond: Double? get() {
        val first = firstTokenMs ?: return null
        val writing = totalMs - first
        return if (writing < 200 || chars < 40) null else (chars / 6.0) * 1000.0 / writing
    }
}

/** Chronomètre d'un tour, alimenté par les événements de l'orchestrateur. */
class TurnTimer(private val voice: Boolean, private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val start = clock()
    private var engine: String? = null
    private var specialist: String? = null
    private var researchStart: Long? = null
    private var researchMs: Long? = null
    private var first: Long? = null
    private var chars = 0
    private var failed = false

    fun on(e: OrchestratorEvent) {
        when (e) {
            is OrchestratorEvent.Planned -> { engine = e.provider; specialist = e.intent.primary.name.lowercase() }
            is OrchestratorEvent.ResearchStarted -> researchStart = clock()
            is OrchestratorEvent.ResearchDone -> researchStart?.let { researchMs = clock() - it }
            is OrchestratorEvent.Token -> { if (first == null && e.text.isNotBlank()) first = clock() - start; chars += e.text.length }
            is OrchestratorEvent.Failed -> failed = true
            else -> Unit
        }
    }

    fun finish(): TurnMetrics = TurnMetrics(voice, engine, specialist, researchMs, first, clock() - start, chars, failed)
}

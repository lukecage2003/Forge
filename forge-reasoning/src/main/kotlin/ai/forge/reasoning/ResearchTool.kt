package ai.forge.reasoning

/** Résultat d'une recherche, déjà encadré (contenus Web = données non fiables). */
data class ResearchContext(
    val promptContext: String,
    val bibliography: String,
    val sourceCount: Int,
    val refusedCount: Int,
    val suspiciousCount: Int,
    /** (titre, url) de chaque source numérotée, dans l'ordre [1], [2]… */
    val citations: List<Pair<String, String>> = emptyList(),
)

/** Outil de recherche Web (implémenté par forge-browser côté application). */
fun interface ResearchTool {
    suspend fun research(query: String): ResearchContext
}

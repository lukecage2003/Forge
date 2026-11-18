package ai.forge.reasoning

/** Résultat d'une recherche, déjà encadré (contenus Web = données non fiables). */
data class ResearchContext(
    val promptContext: String,
    val bibliography: String,
    val sourceCount: Int,
    val refusedCount: Int,
    val suspiciousCount: Int,
)

/** Outil de recherche Web (implémenté par forge-browser côté application). */
fun interface ResearchTool {
    suspend fun research(query: String): ResearchContext
}

package ai.forge.core.llm

import ai.forge.core.model.ThinkingDepth

/** Préférences de l'utilisateur pour le routage entre moteurs. */
data class RoutingPolicy(
    /** Si vrai, aucun moteur cloud n'est jamais utilisé (mode confidentiel strict). */
    val localOnly: Boolean = false,
    /** Autorise le cloud pour les profondeurs Deep Research / Expert / Production uniquement. */
    val cloudForDeepWorkOnly: Boolean = true,
    /** Coût relatif maximum accepté. */
    val maxRelativeCost: Int = 5,
)

data class RoutingNeeds(
    val depth: ThinkingDepth,
    val needsVision: Boolean = false,
    val estimatedContextTokens: Int = 2_000,
    /** Données confidentielles : force le local quand c'est possible. */
    val sensitiveData: Boolean = false,
)

/**
 * Sélectionne le moteur adapté (§3.2 : « savoir quand utiliser un modèle local ou une API facultative »).
 * Stratégie : filtrer par contraintes dures, puis classer. Local-first : à qualité suffisante, le local gagne.
 */
class ProviderRouter(private val providers: List<LlmProvider>, var policy: RoutingPolicy = RoutingPolicy()) {

    fun candidates(needs: RoutingNeeds): List<LlmProvider> {
        val requiredQuality = when (needs.depth) {
            ThinkingDepth.INSTANT -> 1
            ThinkingDepth.STANDARD -> 2
            ThinkingDepth.DEEP_RESEARCH, ThinkingDepth.PRODUCTION -> 3
            ThinkingDepth.EXPERT -> 4
        }
        val deepWork = needs.depth in setOf(ThinkingDepth.DEEP_RESEARCH, ThinkingDepth.EXPERT, ThinkingDepth.PRODUCTION)
        val cloudAllowed = !policy.localOnly && !needs.sensitiveData && (!policy.cloudForDeepWorkOnly || deepWork)

        return providers
            .filter { it.capabilities.contextTokens >= needs.estimatedContextTokens }
            .filter { !needs.needsVision || it.capabilities.vision }
            .filter { it.capabilities.locality != Locality.CLOUD || cloudAllowed }
            .filter { it.capabilities.relativeCost <= policy.maxRelativeCost }
            .sortedWith(
                compareByDescending<LlmProvider> { it.capabilities.reasoningQuality >= requiredQuality }
                    .thenBy { it.capabilities.locality.ordinal }          // local d'abord
                    .thenByDescending { it.capabilities.reasoningQuality }
                    .thenBy { it.capabilities.relativeCost }
            )
    }

    /** Premier moteur disponible dans l'ordre de préférence, ou null. */
    suspend fun select(needs: RoutingNeeds): LlmProvider? = candidates(needs).firstOrNull { it.isAvailable() }
}

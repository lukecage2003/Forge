package ai.forge.models

import ai.forge.core.model.DataClass
import ai.forge.core.model.TaskCategory
import ai.forge.core.model.ThinkingDepth
import ai.forge.security.EgressPolicy

/** Préférences de l'utilisateur pour le routage entre moteurs. */
data class RoutingPolicy(
    /** Si vrai, aucun moteur cloud n'est jamais utilisé (mode confidentiel strict). */
    val localOnly: Boolean = false,
    /** Autorise le cloud pour les profondeurs Deep Research / Expert / Production uniquement. */
    val cloudForDeepWorkOnly: Boolean = true,
    /** Coût relatif maximum accepté. */
    val maxRelativeCost: Int = 5,
    /** Réseau disponible ? (faux = mode hors-ligne : seuls les moteurs sur l'appareil restent). */
    val networkAvailable: Boolean = true,
    /**
     * Mode rapide : un moteur en ligne autorisé passe AVANT le modèle du téléphone (qui reste
     * utilisé hors-ligne et pour tout ce que la politique de données interdit d'envoyer).
     */
    val preferCloud: Boolean = false,
)

data class RoutingNeeds(
    val depth: ThinkingDepth,
    val needsVision: Boolean = false,
    val estimatedContextTokens: Int = 2_000,
    val task: TaskCategory = TaskCategory.CONVERSATION,
    /** Sensibilité des données transmises : décide si un moteur externe peut les recevoir. */
    val dataClass: DataClass = DataClass.GENERAL,
)

/**
 * Sélectionne le moteur adapté (§3.2). Contraintes dures d'abord (capacités, réseau, confidentialité,
 * autorisations par fournisseur, limites d'usage), puis classement local-first.
 */
class ProviderRouter(
    private val providers: List<LlmProvider>,
    var policy: RoutingPolicy = RoutingPolicy(),
    private val egress: EgressPolicy = EgressPolicy(),
    val usage: UsageMeter = UsageMeter(),
) {
    /** Raison d'exclusion d'un moteur, ou null s'il est éligible. */
    fun exclusionReason(p: LlmProvider, needs: RoutingNeeds): String? {
        val c = p.capabilities
        val deepWork = needs.depth in setOf(ThinkingDepth.DEEP_RESEARCH, ThinkingDepth.EXPERT, ThinkingDepth.PRODUCTION)
        return when {
            c.contextTokens < needs.estimatedContextTokens -> "contexte trop court"
            needs.needsVision && !c.vision -> "pas de vision"
            c.locality != Locality.ON_DEVICE && !policy.networkAvailable -> "réseau indisponible"
            c.relativeCost > policy.maxRelativeCost -> "coût supérieur au plafond"
            c.locality == Locality.CLOUD && policy.localOnly -> "mode strictement local"
            c.locality == Locality.CLOUD && policy.cloudForDeepWorkOnly && !policy.preferCloud && !deepWork -> "cloud réservé au travail approfondi"
            c.locality == Locality.CLOUD -> when (val v = egress.check(p.id, needs.task, needs.dataClass)) {
                is EgressPolicy.Verdict.Refused -> v.reason
                EgressPolicy.Verdict.Allowed -> usage.blockReason(p.id)
            }
            else -> usage.blockReason(p.id)
        }
    }

    fun candidates(needs: RoutingNeeds): List<LlmProvider> {
        val requiredQuality = when (needs.depth) {
            ThinkingDepth.INSTANT -> 1
            ThinkingDepth.STANDARD -> 2
            ThinkingDepth.DEEP_RESEARCH, ThinkingDepth.PRODUCTION -> 3
            ThinkingDepth.EXPERT -> 4
        }
        return providers.filter { exclusionReason(it, needs) == null }
            .sortedWith(
                compareByDescending<LlmProvider> { policy.preferCloud && it.capabilities.locality == Locality.CLOUD }
                    .thenByDescending { it.capabilities.reasoningQuality >= requiredQuality }
                    .thenBy { it.capabilities.locality.ordinal }          // local d'abord
                    .thenByDescending { it.capabilities.reasoningQuality }
                    .thenBy { it.capabilities.relativeCost }
            )
    }

    /** Explication lisible des exclusions (transparence quand aucun moteur n'est retenu). */
    fun explain(needs: RoutingNeeds): String = if (providers.isEmpty()) "aucun moteur configuré"
        else providers.joinToString("; ") { p -> "${p.displayName} : ${exclusionReason(p, needs) ?: "éligible"}" }

    suspend fun select(needs: RoutingNeeds): LlmProvider? = candidates(needs).firstOrNull { it.isAvailable() }
}

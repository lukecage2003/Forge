package ai.forge.core.model

import kotlinx.serialization.Serializable

/** Rôle d'un message dans une conversation. */
@Serializable
enum class Role { SYSTEM, USER, ASSISTANT, TOOL }

@Serializable
data class ChatMessage(
    val role: Role,
    val content: String,
    val name: String? = null,
)

/**
 * Niveaux d'autonomie de FORGE (cahier des charges §1.3).
 * L'ordre est significatif : un niveau supérieur implique davantage de risques.
 */
@Serializable
enum class AutonomyLevel(val label: String) {
    L0_CONVERSATION("Conversation et analyse"),
    L1_PROPOSE("Préparation et proposition d'actions"),
    L2_REVERSIBLE("Exécution d'actions réversibles autorisées"),
    L3_PREAPPROVED("Automatisations préalablement approuvées"),
    L4_SENSITIVE("Opération sensible : confirmation explicite obligatoire"),
}

/** Profondeur de réflexion (§3.3). */
@Serializable
enum class ThinkingDepth(val label: String, val maxSteps: Int, val verify: Boolean) {
    INSTANT("Instant", maxSteps = 1, verify = false),
    STANDARD("Standard", maxSteps = 3, verify = false),
    DEEP_RESEARCH("Deep Research", maxSteps = 12, verify = true),
    EXPERT("Expert", maxSteps = 8, verify = true),
    PRODUCTION("Production", maxSteps = 20, verify = true),
}

/** Nature épistémique d'un énoncé : FORGE doit toujours distinguer ces catégories (§2.4). */
@Serializable
enum class Epistemic { FACT, HYPOTHESIS, OPINION, UNVERIFIED }

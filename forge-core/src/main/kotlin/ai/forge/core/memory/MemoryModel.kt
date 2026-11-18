package ai.forge.core.memory

import kotlinx.serialization.Serializable

/**
 * Niveaux de mémoire (§4). La mémoire immédiate (conversation en cours) n'est pas persistée ici :
 * elle vit dans [ai.forge.core.orchestrator.ConversationContext].
 */
@Serializable
enum class MemoryKind(val label: String) {
    PERSONAL("Personnelle"),
    PROJECT("Projets"),
    DOCUMENT("Documentaire"),
    PROCEDURAL("Procédurale"),
    EPISODIC("Épisodique"),
}

/** Provenance d'un souvenir : FORGE doit toujours pouvoir dire d'où vient ce qu'il sait. */
@Serializable
data class Provenance(
    val type: Type,
    val reference: String,
    val excerpt: String? = null,
) {
    @Serializable
    enum class Type { USER_EXPLICIT, CONVERSATION, DOCUMENT, WEB, CORRECTION, SYSTEM }
}

@Serializable
data class MemoryItem(
    val id: String,
    val kind: MemoryKind,
    val text: String,
    /** Clé sémantique optionnelle (ex. « preference.voix.vitesse ») — permet de détecter les contradictions. */
    val key: String? = null,
    val project: String? = null,
    val tags: Set<String> = emptySet(),
    val provenance: Provenance,
    val createdAtMs: Long,
    val updatedAtMs: Long = createdAtMs,
    /** Fin de conservation ; null = conservation jusqu'à suppression par l'utilisateur. */
    val expiresAtMs: Long? = null,
    val confidence: Double = 1.0,
    val pinned: Boolean = false,
    val supersededBy: String? = null,
    val embedding: List<Float>? = null,
) {
    val active: Boolean get() = supersededBy == null
}

data class MemoryHit(val item: MemoryItem, val score: Double, val why: String)

data class MemoryQuery(
    val text: String,
    val kinds: Set<MemoryKind> = MemoryKind.entries.toSet(),
    val project: String? = null,
    val limit: Int = 8,
    val includeSuperseded: Boolean = false,
)

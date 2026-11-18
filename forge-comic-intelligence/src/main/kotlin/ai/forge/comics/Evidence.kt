package ai.forge.comics

import kotlinx.serialization.Serializable

/** Hiérarchie des sources (§8.1) — rang 1 = la plus fiable. */
@Serializable
enum class SourceTier(val rank: Int, val label: String, val isPrimary: Boolean) {
    ORIGINAL_COMIC(1, "Comic original / édition officielle consultable", true),
    OFFICIAL_COLLECTION(2, "Édition intégrale officielle", true),
    OFFICIAL_EDITORIAL(3, "Matériel éditorial officiel", false),
    CREATOR_STATEMENT(4, "Déclaration vérifiable d'auteur/artiste", false),
    SPECIALIZED_BOOK(5, "Ouvrage spécialisé fiable", false),
    RECOGNIZED_DATABASE(6, "Base de données reconnue", false),
    WIKI_COMMUNITY(7, "Wiki / communauté", false),
}

/** Niveaux de preuve (§8.2). */
@Serializable
enum class EvidenceLevel(val code: String, val label: String) {
    P1("P1", "Preuve explicite et directement vérifiable"),
    P2("P2", "Preuve forte (contexte ou plusieurs éléments)"),
    P3("P3", "Interprétation raisonnable"),
    P4("P4", "Source secondaire uniquement"),
    P5("P5", "Hypothèse, spéculation ou information contestée"),
}

@Serializable
enum class VerificationStatus { UNVERIFIED, PRIMARY_CHECKED, CONTRADICTED, PRIMARY_UNAVAILABLE }

/** Référence précise d'un passage. Les champs inconnus restent null : jamais de valeur inventée. */
@Serializable
data class ComicReference(
    val title: String,
    val issue: String? = null,
    val edition: String? = null,
    val year: Int? = null,
    val page: Int? = null,
    val panel: Int? = null,
    /** Identifiant du fichier source (ex. ID Google Drive) si consulté. */
    val sourceFileId: String? = null,
    val sourceFileName: String? = null,
) {
    fun display(): String = buildString {
        append(title)
        issue?.let { append(" #").append(it) }
        edition?.let { append(" (").append(it).append(")") }
        year?.let { append(", ").append(it) }
        page?.let { append(", p. ").append(it) }
        panel?.let { append(", case ").append(it) }
    }
}

@Serializable
data class EvidenceSource(
    val tier: SourceTier,
    val reference: ComicReference? = null,
    val url: String? = null,
    val description: String,
    /** La source a-t-elle pu être consultée ? (lien mort, fichier absent → false) */
    val accessible: Boolean = true,
) {
    /** Une source sans référence ni lien ne peut être retrouvée par personne : elle ne compte pas. */
    val locatable: Boolean get() = url != null || (reference != null && (reference.issue != null || reference.sourceFileId != null))
}

/** Entrée du Claim Ledger (§8.2). */
@Serializable
data class Claim(
    val id: String,
    val statement: String,
    val subject: String,
    val sources: List<EvidenceSource>,
    val evidenceDescription: String,
    val level: EvidenceLevel,
    val contradictions: List<String> = emptyList(),
    val status: VerificationStatus = VerificationStatus.UNVERIFIED,
    val dateIso: String? = null,
    /** Citation exacte relevée dans la source (pour vérifier les citations des scripts). */
    val verbatimQuote: String? = null,
    /** Continuité ou version du personnage (ex. « Terre-616 », « Ultimate », « MCU », « New 52 »). */
    val continuity: String? = null,
) {
    val bestTier: SourceTier? get() = sources.minByOrNull { it.tier.rank }?.tier
    val primaryReference: ComicReference? get() = sources.filter { it.tier.isPrimary }.mapNotNull { it.reference }.firstOrNull()
}

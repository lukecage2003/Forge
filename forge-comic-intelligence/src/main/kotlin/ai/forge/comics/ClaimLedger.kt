package ai.forge.comics

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Registre des affirmations avec garde-fous (§8.2, §8.3).
 * Invariants appliqués à chaque enregistrement :
 * - P1 exige une source primaire vérifiée avec numéro ET page.
 * - P2 exige au moins une source primaire, ou deux sources indépendantes de rang ≤ 4.
 * - Une affirmation fondée uniquement sur des sources secondaires ne peut dépasser P4.
 * - Une affirmation contredite ne peut dépasser P3.
 * Le registre rétrograde automatiquement un niveau injustifié et explique pourquoi.
 */
class ClaimLedger {
    private val claims = linkedMapOf<String, Claim>()
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    data class Admission(val claim: Claim, val downgraded: Boolean, val notes: List<String>)

    fun record(claim: Claim): Admission {
        val notes = mutableListOf<String>()
        val justified = maxJustifiedLevel(claim, notes)
        val final = if (claim.level.ordinal < justified.ordinal) claim.copy(level = justified) else claim
        claims[final.id] = final
        return Admission(final, final.level != claim.level, notes)
    }

    fun maxJustifiedLevel(c: Claim, notes: MutableList<String> = mutableListOf()): EvidenceLevel {
        val primaries = c.sources.filter { it.tier.isPrimary }
        val checked = c.status == VerificationStatus.PRIMARY_CHECKED
        val completePrimary = primaries.any { it.reference?.issue != null && it.reference.page != null }
        var max = EvidenceLevel.P1
        if (!(checked && completePrimary)) {
            max = EvidenceLevel.P2
            notes += "P1 refusé : source primaire non vérifiée ou référence incomplète (numéro et page requis)."
        }
        val strongSecondaries = c.sources.count { it.tier.rank <= 4 }
        if (primaries.isEmpty() && strongSecondaries < 2) {
            max = maxOf(max, EvidenceLevel.P3)
            notes += "P2 refusé : aucune source primaire et moins de deux sources fortes."
        }
        if (primaries.isEmpty() && c.sources.all { it.tier.rank >= 5 }) {
            max = maxOf(max, EvidenceLevel.P4)
            notes += "Sources uniquement secondaires (ouvrage, base, wiki) : niveau plafonné à P4."
        }
        if (c.sources.isEmpty()) {
            max = EvidenceLevel.P5
            notes += "Aucune source : hypothèse (P5)."
        }
        if (c.status == VerificationStatus.CONTRADICTED || c.contradictions.isNotEmpty()) {
            max = maxOf(max, EvidenceLevel.P3)
            notes += "Éléments contradictoires : niveau plafonné à P3."
        }
        return max
    }

    fun get(id: String) = claims[id]
    fun all(): List<Claim> = claims.values.toList()
    fun bySubject(subject: String) = claims.values.filter { it.subject.equals(subject, ignoreCase = true) }

    /** Affirmations qui ne doivent pas apparaître comme des faits établis dans un script. */
    fun notPublishableAsFact(): List<Claim> = claims.values.filter { it.level >= EvidenceLevel.P4 }

    fun exportJson(): String = json.encodeToString(all())

    /** Présentation d'un résultat de recherche (§7.3) — champs inconnus explicitement signalés. */
    fun formatResult(c: Claim): String = buildString {
        appendLine("• ${c.statement}  [${c.level.code} — ${c.level.label}]")
        val ref = c.primaryReference
        if (ref != null) {
            appendLine("  Référence : ${ref.display()}")
            if (ref.page == null) appendLine("  Page : non identifiée")
            if (ref.panel == null) appendLine("  Case : non identifiée")
            ref.sourceFileName?.let { appendLine("  Fichier : $it") }
        } else appendLine("  Référence primaire : non disponible")
        appendLine("  Preuve : ${c.evidenceDescription}")
        appendLine("  Vérification : ${c.status.name}")
        if (c.contradictions.isNotEmpty()) appendLine("  Contradictions : ${c.contradictions.joinToString("; ")}")
    }.trimEnd()
}

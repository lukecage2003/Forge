package ai.forge.editorial

import ai.forge.comics.Claim
import ai.forge.comics.EvidenceLevel
import ai.forge.comics.VerificationStatus
import ai.forge.core.text.TextUtils
import java.time.Year

/** Statut éditorial d'un script (§9.5). */
enum class ScriptStatus(val label: String) {
    DRAFT("Brouillon"),
    NEEDS_REVIEW("À corriger"),
    VERIFIED("Vérifié"),
    REJECTED("Refusé : sources fictives, inaccessibles ou insuffisantes"),
}

enum class VerificationIssueKind(val label: String) {
    CONTINUITY("Erreur de continuité"),
    VERSION_CONFUSION("Confusion entre versions d'un personnage"),
    DATE("Date incorrecte ou non étayée"),
    QUOTE("Citation incorrecte ou non vérifiée"),
    UNSUPPORTED("Affirmation sans preuve"),
    SHORTCUT("Raccourci narratif"),
    SOURCE_CONTRADICTION("Contradiction entre sources"),
    EXTRAPOLATION("Extrapolation présentée comme un fait"),
    SOURCE_INSUFFICIENT("Source fictive, inaccessible ou insuffisante"),
}

data class VerificationIssue(
    val kind: VerificationIssueKind,
    val segment: String,
    val detail: String,
    val blocking: Boolean,
)

data class VerificationReport(val status: ScriptStatus, val issues: List<VerificationIssue>) {
    val blocking get() = issues.filter { it.blocking }
    fun summary(): String = buildString {
        appendLine("Statut : ${status.label}")
        if (issues.isEmpty()) appendLine("Aucun problème détecté par les contrôles automatiques.")
        issues.groupBy { it.kind }.forEach { (k, l) ->
            appendLine("${k.label} (${l.size}) :")
            l.forEach { appendLine("  ${if (it.blocking) "■" else "□"} [${it.segment}] ${it.detail}") }
        }
        appendLine("Rappel : ces contrôles automatiques complètent la relecture du Fact Checker, ils ne la remplacent pas.")
    }.trimEnd()
}

/**
 * Vérification éditoriale (§9.5). Contrôles déterministes, explicables, sur un script et son registre
 * d'affirmations. Un script n'obtient « Vérifié » que si :
 * - aucune anomalie bloquante n'est détectée ;
 * - chaque affirmation citée existe, a au moins une source localisable et accessible ;
 * - chaque affirmation assertée comme un fait est au niveau P1 ou P2.
 */
object EditorialVerifier {
    private val hedges = Regex("\\b(peut-etre|semble|sembler|on peut penser|on peut supposer|selon|d'apres|probablement|sans doute|il est possible|hypothese|theorie|interpretation|suggere|laisse entendre)\\b")
    private val absolutes = Regex("\\b(toujours|jamais|tout le monde sait|officiellement|canoniquement|il est prouve|c'est un fait|sans aucun doute|le plus puissant|la plus puissante|invincible|pour la premiere fois)\\b")
    private val factualMarkers = Regex("\\b(1[89]\\d{2}|20\\d{2})\\b|#\\d+|\\b(numero|page|tome|volume)\\s+\\d+|\\b(premiere apparition|cree par|crée par|ecrit par|dessine par)\\b")
    private val quotePattern = Regex("«\\s*([^»]{6,})\\s*»|\"([^\"]{6,})\"")
    private val yearPattern = Regex("\\b(1[89]\\d{2}|20\\d{2})\\b")

    fun verify(script: ScriptDocument, ledger: Map<String, Claim>, currentYear: Int = Year.now().value): VerificationReport {
        val issues = mutableListOf<VerificationIssue>()
        fun add(k: VerificationIssueKind, seg: String, d: String, blocking: Boolean) = issues.add(VerificationIssue(k, seg, d, blocking))

        for (seg in script.segments) {
            val claims = seg.claimIds.mapNotNull { id -> ledger[id] ?: run {
                add(VerificationIssueKind.SOURCE_INSUFFICIENT, seg.heading, "Affirmation $id introuvable dans le registre.", true); null
            } }
            val folded = TextUtils.fold(seg.narration)

            // Sources : fictives (non localisables), inaccessibles, ou insuffisantes.
            claims.forEach { c ->
                when {
                    c.sources.isEmpty() || c.sources.none { it.locatable } ->
                        add(VerificationIssueKind.SOURCE_INSUFFICIENT, seg.heading, "« ${c.statement} » : aucune source localisable (référence ou lien).", true)
                    c.sources.none { it.accessible } ->
                        add(VerificationIssueKind.SOURCE_INSUFFICIENT, seg.heading, "« ${c.statement} » : aucune source accessible.", true)
                }
                if (c.contradictions.isNotEmpty() || c.status == VerificationStatus.CONTRADICTED)
                    add(VerificationIssueKind.SOURCE_CONTRADICTION, seg.heading, "« ${c.statement} » : ${c.contradictions.joinToString("; ").ifEmpty { "sources contradictoires" }}", !hedges.containsMatchIn(folded))
                if (c.level >= EvidenceLevel.P3 && !hedges.containsMatchIn(folded))
                    add(VerificationIssueKind.EXTRAPOLATION, seg.heading, "« ${c.statement} » est ${c.level.code} mais présentée sans nuance (ajouter « selon », « on peut penser »…).", true)
            }

            // Continuité / versions.
            val continuities = claims.mapNotNull { it.continuity?.lowercase() }.toSet()
            if (continuities.size > 1 && seg.continuity == null)
                add(VerificationIssueKind.VERSION_CONFUSION, seg.heading, "Le segment mêle plusieurs versions (${continuities.joinToString()}) sans le dire au spectateur.", true)
            seg.continuity?.lowercase()?.let { sc ->
                claims.filter { c -> c.continuity?.lowercase()?.let { it != sc } == true }.forEach {
                    add(VerificationIssueKind.CONTINUITY, seg.heading, "Le segment parle de « ${seg.continuity} » mais s'appuie sur « ${it.statement} » (${it.continuity}).", true)
                }
            }

            // Dates.
            val claimYears = claims.flatMap { c -> c.sources.mapNotNull { it.reference?.year } + listOfNotNull(c.dateIso?.take(4)?.toIntOrNull()) }.toSet()
            yearPattern.findAll(seg.narration).map { it.value.toInt() }.forEach { y ->
                when {
                    y < 1930 || y > currentYear -> add(VerificationIssueKind.DATE, seg.heading, "Année $y improbable pour une publication comics.", false)
                    claimYears.isNotEmpty() && y !in claimYears -> add(VerificationIssueKind.DATE, seg.heading, "Année $y absente des références citées (${claimYears.sorted().joinToString()}).", true)
                    claimYears.isEmpty() -> add(VerificationIssueKind.DATE, seg.heading, "Année $y sans affirmation qui l'établisse.", true)
                }
            }

            // Citations : doivent correspondre exactement à une citation relevée.
            quotePattern.findAll(seg.narration).forEach { m ->
                val q = (m.groups[1] ?: m.groups[2])!!.value.trim()
                val nq = normalizeQuote(q)
                val known = claims.mapNotNull { it.verbatimQuote }.map(::normalizeQuote)
                when {
                    known.any { it == nq } -> Unit
                    known.any { it.contains(nq) } -> add(VerificationIssueKind.QUOTE, seg.heading, "Citation tronquée : « $q ». Vérifier que la coupe ne change pas le sens.", false)
                    else -> add(VerificationIssueKind.QUOTE, seg.heading, "Citation « ${q.take(60)} » absente des citations relevées dans les sources.", true)
                }
            }

            // Affirmations sans preuve et raccourcis.
            if (claims.isEmpty() && factualMarkers.containsMatchIn(folded))
                add(VerificationIssueKind.UNSUPPORTED, seg.heading, "Le segment avance des faits précis (dates, numéros, auteurs) sans affirmation enregistrée.", true)
            absolutes.findAll(folded).forEach { m ->
                val strong = claims.isNotEmpty() && claims.all { it.level <= EvidenceLevel.P2 }
                if (!strong) add(VerificationIssueKind.SHORTCUT, seg.heading, "« ${m.value} » : formulation absolue non étayée par une preuve P1/P2.", false)
            }
        }

        val usedClaims = script.segments.flatMap { it.claimIds }.mapNotNull { ledger[it] }
        val status = when {
            issues.any { it.kind == VerificationIssueKind.SOURCE_INSUFFICIENT } -> ScriptStatus.REJECTED
            issues.any { it.blocking } -> ScriptStatus.NEEDS_REVIEW
            script.segments.isEmpty() -> ScriptStatus.DRAFT
            usedClaims.isEmpty() && script.format.kind != FormatKind.SHORT_VERTICAL -> ScriptStatus.NEEDS_REVIEW.also {
                issues += VerificationIssue(VerificationIssueKind.UNSUPPORTED, "Script", "Aucune affirmation sourcée : impossible de certifier le contenu.", true)
            }
            else -> ScriptStatus.VERIFIED
        }
        return VerificationReport(status, issues)
    }

    private fun normalizeQuote(q: String) = TextUtils.fold(q).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
}

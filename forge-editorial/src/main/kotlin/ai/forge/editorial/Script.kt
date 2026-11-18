package ai.forge.editorial

import ai.forge.comics.Claim
import ai.forge.comics.EvidenceLevel
import ai.forge.core.text.TextUtils
import ai.forge.writing.WritingAnalyzer
import kotlinx.serialization.Serializable

@Serializable
data class ScriptSegment(
    val heading: String,
    val narration: String,
    val deliveryNotes: String? = null,
    val transition: String? = null,
    val visuals: List<String> = emptyList(),
    /** Identifiants d'affirmations du Claim Ledger appuyant ce segment. */
    val claimIds: List<String> = emptyList(),
)

/** Script complet (§9.4). */
@Serializable
data class ScriptDocument(
    val title: String,
    val promise: String,
    val audience: String,
    val format: FormatSpec,
    val angle: String,
    val segments: List<ScriptSegment>,
    val sources: List<String> = emptyList(),
) {
    val narrationText: String get() = segments.joinToString("\n\n") { it.narration }
    val wordCount: Int get() = TextUtils.wordCount(narrationText)
    val estimatedSeconds: Int get() = wordCount * 60 / format.wordsPerMinute
}

data class QcIssue(val severity: Severity, val message: String) {
    enum class Severity { BLOQUANT, MAJEUR, MINEUR }
}

/** Contrôle qualité déterministe d'un script (complète la relecture par le Quality Controller). */
object ScriptQualityControl {
    fun check(script: ScriptDocument, ledger: Map<String, Claim>): List<QcIssue> = buildList {
        val f = script.format
        val delta = script.wordCount - f.targetWords
        if (kotlin.math.abs(delta) > f.toleranceWords) {
            add(QcIssue(QcIssue.Severity.MAJEUR,
                "Longueur : ${script.wordCount} mots pour une cible de ${f.targetWords} (± ${f.toleranceWords}) " +
                    "→ durée estimée ${script.estimatedSeconds}s au lieu de ${f.targetSeconds}s."))
        }
        if (script.promise.isBlank()) add(QcIssue(QcIssue.Severity.BLOQUANT, "Promesse éditoriale absente."))
        if (script.segments.isEmpty()) add(QcIssue(QcIssue.Severity.BLOQUANT, "Script vide."))

        script.segments.firstOrNull()?.let { first ->
            val hookWords = f.hookMaxSeconds * f.wordsPerMinute / 60
            val firstSentence = TextUtils.sentences(first.narration).firstOrNull().orEmpty()
            if (TextUtils.wordCount(firstSentence) > hookWords.coerceAtLeast(6)) {
                add(QcIssue(QcIssue.Severity.MAJEUR,
                    "Accroche trop lente : la première phrase dépasse ~$hookWords mots (${f.hookMaxSeconds}s)."))
            }
        }
        if (f.kind == FormatKind.SHORT_VERTICAL && script.segments.size > 5) {
            add(QcIssue(QcIssue.Severity.MAJEUR, "Short : trop de segments — une seule idée par Short."))
        }
        script.segments.forEach { s ->
            s.claimIds.forEach { id ->
                val c = ledger[id]
                when {
                    c == null -> add(QcIssue(QcIssue.Severity.BLOQUANT, "« ${s.heading} » cite l'affirmation $id absente du registre."))
                    c.level >= EvidenceLevel.P4 -> add(QcIssue(QcIssue.Severity.MAJEUR,
                        "« ${s.heading} » s'appuie sur ${c.level.code} (${c.statement}) : à formuler comme non confirmé."))
                }
            }
            if (Regex("\\b(page|p\\.)\\s*\\d+|#\\d+").containsMatchIn(s.narration) && s.claimIds.isEmpty()) {
                add(QcIssue(QcIssue.Severity.BLOQUANT,
                    "« ${s.heading} » cite un numéro ou une page sans affirmation enregistrée : risque d'invention."))
            }
        }
        if (f.kind != FormatKind.SHORT_VERTICAL && script.sources.isEmpty()) {
            add(QcIssue(QcIssue.Severity.MAJEUR, "Aucune source listée."))
        }
        val reps = WritingAnalyzer.repetitions(script.narrationText, minCount = 6)
        if (reps.isNotEmpty()) add(QcIssue(QcIssue.Severity.MINEUR, "Répétitions : ${reps.joinToString { "${it.first} ×${it.second}" }}"))
    }
}

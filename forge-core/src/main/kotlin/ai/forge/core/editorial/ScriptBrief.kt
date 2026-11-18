package ai.forge.core.editorial

import ai.forge.core.comics.Claim

/** Commande d'un script : ce que le rédacteur en chef transmet à la chaîne éditoriale. */
data class ScriptBrief(
    val topic: String,
    val format: FormatSpec,
    val audience: String = "Fans de comics francophones, du curieux au connaisseur",
    val angle: String? = null,
    val verifiedClaims: List<Claim> = emptyList(),
    /** Script long existant à partir duquel reconstruire un Short. */
    val sourceScript: ScriptDocument? = null,
)

object ScriptPromptBuilder {
    fun build(role: EditorialRole, brief: ScriptBrief): String = buildString {
        appendLine(role.instructions())
        appendLine()
        appendLine("Sujet : ${brief.topic}")
        appendLine("Format : ${brief.format.label} — ${brief.format.targetSeconds} s, ~${brief.format.targetWords} mots (± ${brief.format.toleranceWords}).")
        appendLine("Public : ${brief.audience}")
        brief.angle?.let { appendLine("Angle imposé : $it") }
        appendLine("Structure attendue : ${brief.format.beats.joinToString(" → ")}")
        appendLine("Principes : ${brief.format.principles.joinToString("; ")}")
        if (brief.format.kind == FormatKind.SHORT_VERTICAL && brief.sourceScript != null) {
            appendLine()
            appendLine("""IMPORTANT : ne coupe pas le script long. Choisis UNE idée autonome qu'il contient,
                |et reconstruis une narration complète autour d'elle, avec son propre hook et sa propre chute.""".trimMargin())
            appendLine("Script long de référence : « ${brief.sourceScript.title} » — promesse : ${brief.sourceScript.promise}")
        }
        appendLine()
        if (brief.verifiedClaims.isEmpty()) {
            appendLine("Aucune affirmation vérifiée n'est fournie : n'avance aucun numéro, page ou citation précise. Signale les points à vérifier par [À VÉRIFIER].")
        } else {
            appendLine("Affirmations disponibles (n'utilise QUE celles-ci pour les faits précis, en citant leur identifiant) :")
            brief.verifiedClaims.forEach { appendLine("- [${it.id}] ${it.level.code} : ${it.statement}") }
            appendLine("Les affirmations P4/P5 doivent être formulées comme non confirmées.")
        }
    }.trim()
}

package ai.forge.comics

/** Preuve disponible pour un débat, identifiée par une étiquette citée dans la réponse ([B1], [S2]…). */
data class DebateEvidence(val tag: String, val description: String, val tier: SourceTier, val verified: Boolean)

data class DebateArgument(val text: String, val tags: List<String>)

data class DebateAudit(val arguments: List<DebateArgument>, val unsourced: List<DebateArgument>, val weak: List<Pair<DebateArgument, String>>) {
    val solid: Int get() = arguments.size - unsourced.size - weak.size
    fun report(): String = buildString {
        appendLine("**Solidité : $solid/${arguments.size} argument(s) appuyé(s) par une preuve vérifiée**")
        unsourced.forEach { appendLine("- ❌ Non sourcé : « ${it.text.take(90)} » → fournissez la page ou retirez l'argument") }
        weak.forEach { (a, why) -> appendLine("- ⚠️ Preuve faible ($why) : « ${a.text.take(90)} »") }
        if (unsourced.isEmpty() && weak.isEmpty() && arguments.isNotEmpty()) append("- ✅ Chaque argument repose sur une preuve vérifiée.")
    }.trim()
}

/**
 * Débat comics (§8) : chaque argument doit citer une preuve fournie ; les sources communautaires
 * (wikis) ne valent que comme pistes. Le contrôle est déterministe : il ne dépend pas du modèle.
 */
object DebateCoach {
    fun prompt(evidence: List<DebateEvidence>): String = buildString {
        appendLine("Mode débat comics. Tu débats comme un stratège : précis, offensif sur les faits, honnête sur les faiblesses.")
        appendLine("Règle absolue : chaque argument cite au moins une preuve par son étiquette entre crochets, ex. [B1].")
        appendLine("Un argument sans preuve disponible doit être écrit « (non sourcé) ». Les wikis ne sont que des pistes.")
        appendLine("Distingue ce que montre la page (fait) de ton interprétation (interprétation). Signale les contre-exemples connus.")
        if (evidence.isEmpty()) appendLine("Aucune preuve disponible : dis-le, propose à l'utilisateur de fournir les pages, et n'avance aucun numéro ni page.")
        else { appendLine("Preuves disponibles :"); evidence.forEach { appendLine("${it.tag} (${if (it.verified) "vérifiée" else it.tier.label}) : ${it.description}") } }
        appendLine("""Format :
            |### POSITION
            |…
            |### ARGUMENTS
            |1. … [B1]
            |### FAIBLESSES DE MA POSITION
            |…
            |### VERDICT
            |…""".trimMargin())
    }

    private val tagRx = Regex("\\[(B\\d+|S\\d+|P\\d+)]")

    fun parseArguments(answer: String): List<DebateArgument> {
        val start = Regex("(?im)^#{2,3}\\s*ARGUMENTS\\s*$").find(answer)?.range?.last ?: return emptyList()
        val rest = answer.substring(start + 1)
        val end = Regex("(?m)^#{2,3}\\s*\\S").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end).lines().map { it.trim() }.filter { Regex("^(\\d+[.)]|[-•])\\s+").containsMatchIn(it) }
            .map { line -> DebateArgument(line.replace(Regex("^(\\d+[.)]|[-•])\\s+"), ""), tagRx.findAll(line).map { it.groupValues[1] }.toList()) }
    }

    fun audit(answer: String, evidence: List<DebateEvidence>): DebateAudit {
        val byTag = evidence.associateBy { it.tag }
        val args = parseArguments(answer)
        val unsourced = args.filter { a -> a.tags.none { it in byTag } }
        val weak = args.filter { it !in unsourced }.mapNotNull { a ->
            val ev = a.tags.mapNotNull { byTag[it] }
            when {
                ev.any { it.verified } -> null
                ev.all { it.tier == SourceTier.WIKI_COMMUNITY } -> a to "wiki uniquement"
                else -> a to "non vérifiée"
            }
        }
        return DebateAudit(args, unsourced, weak)
    }

    private val debateCue = Regex("(debat|debattre|qui gagne|qui est le plus|plus fort|plus puissant|versus|\\bvs\\b|contre qui|argumente|prouve)")
    fun isDebate(foldedText: String) = debateCue.containsMatchIn(foldedText)
}

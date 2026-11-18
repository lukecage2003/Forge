package ai.forge.academy

/** Briefing du matin : court, concret, rien d'inventé (uniquement ce que Forge connaît). */
object MorningBriefing {
    data class Inputs(val projects: List<String>, val dueCards: Int, val lastScriptTitle: String?, val hour: Int)

    fun compose(i: Inputs): Pair<String, String>? {
        val parts = buildList {
            if (i.dueCards > 0) add("${i.dueCards} fiche${if (i.dueCards > 1) "s" else ""} à réviser")
            i.lastScriptTitle?.let { add("script en cours : « ${it.take(50)} »") }
            i.projects.take(2).forEach { add(it.take(70)) }
        }
        if (parts.isEmpty()) return null                      // rien d'utile : pas de notification
        val title = when (i.hour) { in 5..11 -> "Bonjour. Voici votre journée."; in 12..17 -> "Le point de l'après-midi"; else -> "Le point du soir" }
        return title to parts.joinToString(" · ").replaceFirstChar { it.uppercase() }
    }

    /** Question envoyée à Forge quand on touche la notification. */
    const val PROMPT = "Fais-moi mon briefing du jour : où en sont mes projets, mes révisions et mes vidéos, et quelle est la priorité aujourd'hui ?"
}

package ai.forge.academy

/** Niveau de l'explication (§10 : « explications adaptées au niveau »). */
enum class Level(val label: String, val instruction: String) {
    BEGINNER("Débutant", "Vocabulaire simple, pas de prérequis supposé, une idée à la fois."),
    INTERMEDIATE("Intermédiaire", "Vocabulaire du cours, liens entre notions."),
    ADVANCED("Avancé", "Formulation rigoureuse, cas limites, nuances."),
}

/** Différentes manières d'expliquer une même notion. */
enum class ExplanationStyle(val label: String, val instruction: String) {
    DEFINITION("Définition", "Donne la définition exacte du cours, puis reformule-la."),
    ANALOGY("Analogie", "Explique par une analogie de la vie courante, puis montre ses limites."),
    EXAMPLE("Exemple concret", "Pars d'un exemple concret, puis remonte à la règle."),
    STEP_BY_STEP("Pas à pas", "Découpe le raisonnement en étapes numérotées."),
    SOCRATIC("Questions guidées", "Ne donne pas la réponse : pose une question à la fois pour faire trouver l'étudiant."),
    CONTRAST("Par contraste", "Explique ce que la notion n'est pas, en la comparant à une notion voisine souvent confondue."),
}

/**
 * Prompts pédagogiques. Règle centrale : chaque paragraphe est marqué [Cours : réf] s'il s'appuie sur
 * un passage fourni, ou [Hors cours] s'il s'agit d'une explication extérieure (§10).
 */
object AcademyPrompts {
    const val COURSE_TAG = "[Cours"
    const val EXTERNAL_TAG = "[Hors cours]"

    fun explain(notion: String, passages: List<Pair<String, CourseChunk>>, level: Level, style: ExplanationStyle, voice: Boolean): String = buildString {
        appendLine("Explique la notion : « $notion ».")
        appendLine("Niveau : ${level.label}. ${level.instruction}")
        appendLine("Manière : ${style.label}. ${style.instruction}")
        appendLine("Commence chaque paragraphe par $COURSE_TAG : <référence>] s'il s'appuie sur un passage ci-dessous, ou par $EXTERNAL_TAG sinon.")
        appendLine("Conserve la terminologie du professeur. N'invente aucune référence de page.")
        if (voice) appendLine("Réponse orale : pas de liste, phrases courtes ; dis « selon ton cours » ou « en dehors du cours » au lieu des balises.")
        if (passages.isEmpty()) appendLine("Aucun passage de cours n'a été trouvé : dis-le clairement, puis n'utilise que $EXTERNAL_TAG.")
        passages.forEach { (title, c) -> appendLine("--- Passage (${c.citation(title)}) ---\n${c.text}") }
    }

    /** Dialogue oral de révision : Forge interroge, écoute, corrige, relance. */
    fun oralCoach(notions: List<String>): String = """
        Tu fais réviser l'étudiant à l'oral sur : ${notions.joinToString(", ")}.
        Pose UNE question à la fois, attends sa réponse, dis ce qui est juste, corrige ce qui est faux
        en t'appuyant sur le cours, puis passe à la suivante. Si l'étudiant bloque, donne un indice
        avant la réponse. Reste bref, encourageant sans flatter. À la fin, résume les points à revoir.
    """.trimIndent()

    data class GroundingReport(val untagged: List<String>, val courseParagraphs: Int, val externalParagraphs: Int, val unknownReferences: List<String>) {
        val ok get() = untagged.isEmpty() && unknownReferences.isEmpty()
    }

    /** Vérifie que chaque paragraphe déclare son origine et que les références citées existent. */
    fun checkGrounding(answer: String, validReferences: Set<String>): GroundingReport {
        val paras = answer.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }
        val untagged = paras.filter { !it.startsWith(COURSE_TAG) && !it.startsWith(EXTERNAL_TAG) }
        val refs = Regex("\\[Cours\\s*:\\s*([^\\]]+)]").findAll(answer).map { it.groupValues[1].trim() }.toList()
        return GroundingReport(untagged.map { it.take(60) }, paras.count { it.startsWith(COURSE_TAG) },
            paras.count { it.startsWith(EXTERNAL_TAG) }, refs.filter { it !in validReferences })
    }
}

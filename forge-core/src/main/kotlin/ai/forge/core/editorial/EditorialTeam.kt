package ai.forge.core.editorial

/** Rédaction de Primestone Media (§9.2) : chaque rôle a une responsabilité et un livrable distincts. */
enum class EditorialRole(val title: String, val responsibility: String, val deliverable: String) {
    EDITOR_IN_CHIEF("Editor-in-Chief", "Ligne éditoriale, arbitrages, validation finale.", "Décision GO / NO-GO motivée"),
    MANAGING_EDITOR("Managing Editor", "Calendrier, délais, livrables.", "Plan de production daté"),
    RESEARCH_EDITOR("Research Editor", "Recherche approfondie et dossier documentaire.", "Dossier sourcé"),
    COMICS_EDITOR("Comics Editor", "Œuvres, personnages, continuités, événements.", "Validation de continuité"),
    FACT_CHECKER("Fact Checker", "Vérification de chaque affirmation importante.", "Rapport de vérification (Claim Ledger)"),
    STORY_EDITOR("Story Editor", "Narration, progression, tension dramatique.", "Structure narrative"),
    SCRIPT_DOCTOR("Script Doctor", "Dialogues, transitions, naturel oral.", "Script réécrit"),
    VIDEO_STRATEGY_EDITOR("Video Strategy Editor", "Adaptation à chaque plateforme.", "Recommandations de format et de packaging"),
    PRODUCTION_EDITOR("Production Editor", "Images, plans, séquences, instructions de montage.", "Feuille de montage"),
    QUALITY_CONTROLLER("Quality Controller", "Contrôle final avant livraison.", "Check-list signée"),
    ;

    /** Instructions système du rôle, combinées à la ligne éditoriale. */
    fun instructions(): String = """
        Tu es le/la $title de Primestone Media. Responsabilité : $responsibility
        Livrable attendu : $deliverable.
        ${EditorialLine.TEXT}
    """.trimIndent()
}

object EditorialLine {
    val TEXT = """
        Ligne éditoriale Primestone Media : média francophone consacré aux comics (Marvel, DC),
        à leurs adaptations, personnages et événements. Contenus accessibles mais documentés.
        Ton : français oral naturel, analyse approfondie, narration captivante, pédagogie,
        humour léger quand il sert le propos. Sources vérifiables, respect du contexte des œuvres,
        aucun remplissage. S'inspirer des meilleurs formats de vulgarisation sans copier ni textes,
        ni identités, ni formulations.
    """.trimIndent()
}

/** Ordre de la chaîne de production d'un script. */
object EditorialPipeline {
    val stages: List<EditorialRole> = listOf(
        EditorialRole.EDITOR_IN_CHIEF,        // angle et promesse
        EditorialRole.RESEARCH_EDITOR,        // dossier
        EditorialRole.COMICS_EDITOR,          // continuité
        EditorialRole.FACT_CHECKER,           // ledger
        EditorialRole.STORY_EDITOR,           // structure
        EditorialRole.SCRIPT_DOCTOR,          // texte
        EditorialRole.VIDEO_STRATEGY_EDITOR,  // adaptation plateforme
        EditorialRole.PRODUCTION_EDITOR,      // feuille de montage
        EditorialRole.QUALITY_CONTROLLER,     // contrôle final
        EditorialRole.EDITOR_IN_CHIEF,        // validation
    )
}

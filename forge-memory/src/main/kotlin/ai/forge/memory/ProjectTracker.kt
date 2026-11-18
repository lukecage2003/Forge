package ai.forge.memory

/**
 * Mémoire des projets : repère, sans modèle (instantané), les phrases où l'utilisateur parle d'un
 * projet en cours — « je travaille sur… », « mon projet X », « ma chaîne X », « pour Primestone… ».
 * Chaque note reste visible, modifiable et supprimable dans l'écran Mémoire.
 */
class ProjectTracker(private val known: Set<String> = setOf("Primestone", "Primestone Media", "Forge Academy")) {
    data class Note(val project: String, val text: String)

    private val name = "(?:«\\s*([^»]{2,40})\\s*»|\"([^\"]{2,40})\"|((?:[A-ZÉÈÀ0-9][\\p{L}0-9'’\\-]*)(?:\\s+(?:[A-ZÉÈÀ0-9][\\p{L}0-9'’\\-]*|de|du|des|la|le|of|the)){0,4}))"
    // Mots d'introduction insensibles à la casse ; le nom, lui, doit être entre guillemets ou en majuscule.
    private val patterns = listOf(
        Regex("(?i:mon|notre) (?i:projet)\\s+(?i:s'appelle\\s+|appelé\\s+|nommé\\s+|:\\s*)?$name"),
        Regex("(?i:ma|notre) (?i:chaîne)(?:\\s+(?i:YouTube|TikTok|Instagram))?\\s+(?i:s'appelle\\s+|:\\s*)?$name"),
        Regex("(?i:je|on|nous)\\s+(?i:travaille|travaillons|bosse|bossons)\\s+(?i:sur)\\s+(?i:le projet\\s+|la série\\s+|la vidéo\\s+)?$name"),
    )
    private val progress = Regex("(?i)\\b(je travaille|on travaille|je bosse|j'ai (fini|terminé|commencé|publié|tourné|monté|écrit)|prochaine (vidéo|étape)|objectif|deadline|date limite|sortie|en cours|il me reste|je prépare|je lance)\\b")

    /** Projet nommé dans la phrase, s'il est connu ou déclaré explicitement. */
    fun detect(text: String, alreadyKnown: Set<String> = emptySet()): Note? {
        val all = known + alreadyKnown
        val t = text.trim()
        // 1. Projet déjà connu cité : on note la phrase si elle parle d'avancement.
        all.sortedByDescending { it.length }.firstOrNull { Regex("(?i)\\b${Regex.escape(it)}\\b").containsMatchIn(t) }?.let { p ->
            return if (progress.containsMatchIn(t)) Note(canonical(p, all), t.take(300)) else null
        }
        // 2. Déclaration explicite d'un nouveau projet.
        for (re in patterns) {
            val m = re.find(t) ?: continue
            val raw = (m.groupValues[1].ifBlank { m.groupValues[2] }.ifBlank { m.groupValues[3] }).trim().trimEnd('.', ',', '!', '?')
            if (raw.length >= 2 && raw.lowercase() !in STOP) return Note(raw, t.take(300))
        }
        return null
    }

    /** Projet mentionné (sans forcément d'avancement), pour rappeler ce qu'on en sait. */
    fun mentioned(text: String, alreadyKnown: Set<String>): String? =
        (known + alreadyKnown).sortedByDescending { it.length }.firstOrNull { Regex("(?i)\\b${Regex.escape(it)}\\b").containsMatchIn(text) }
            ?.let { canonical(it, known + alreadyKnown) }

    private fun canonical(p: String, all: Set<String>) = all.firstOrNull { it.equals(p, ignoreCase = true) } ?: p

    private companion object { val STOP = setOf("ça", "cela", "moi", "toi", "lui", "elle", "un", "une", "le", "la", "les") }
}

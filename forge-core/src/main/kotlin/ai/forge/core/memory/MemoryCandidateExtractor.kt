package ai.forge.core.memory

/**
 * Détecte les informations que l'utilisateur souhaite manifestement conserver.
 * Volontairement conservateur (§4 : « ne transforme pas chaque phrase en souvenir permanent ») :
 * - consigne explicite (« souviens-toi que… ») → enregistrement direct ;
 * - préférence déclarée (« je préfère… ») → proposition soumise à confirmation ;
 * - tout le reste → rien.
 */
class MemoryCandidateExtractor {
    private val explicit = Regex(
        "(?i)\\b(souviens[- ]toi|rappelle[- ]toi|retiens|n'oublie pas|note|m[ée]morise)\\s+(que|qu'|bien que|:)?\\s*(.+)"
    )
    private val preference = Regex("(?i)\\bje (pr[ée]f[èe]re|d[ée]teste|n'aime pas|aime bien|veux toujours|ne veux jamais)\\b(.+)")
    private val forgetCue = Regex("(?i)\\b(oublie|efface|supprime)\\b.*\\b(ça|cela|ce souvenir|que)\\b")

    fun extract(userUtterance: String, conversationId: String, project: String? = null): List<MemoryCandidate> {
        val text = userUtterance.trim()
        if (forgetCue.containsMatchIn(text)) return emptyList()
        explicit.find(text)?.let { m ->
            val content = m.groupValues[3].trim().trimEnd('.', '!')
            if (content.length >= 4) {
                return listOf(MemoryCandidate(
                    kind = if (project != null) MemoryKind.PROJECT else MemoryKind.PERSONAL,
                    text = content.replaceFirstChar { it.uppercase() },
                    provenance = Provenance(Provenance.Type.USER_EXPLICIT, conversationId, text),
                    project = project,
                    needsConfirmation = false,
                ))
            }
        }
        preference.find(text)?.let { m ->
            return listOf(MemoryCandidate(
                kind = MemoryKind.PERSONAL,
                text = "L'utilisateur dit : « ${m.value.trim().trimEnd('.')} »",
                provenance = Provenance(Provenance.Type.CONVERSATION, conversationId, text),
                tags = setOf("préférence"),
                needsConfirmation = true,
            ))
        }
        return emptyList()
    }
}

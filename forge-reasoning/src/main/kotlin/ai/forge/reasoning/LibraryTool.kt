package ai.forge.reasoning

/** Passage trouvé dans la bibliothèque personnelle, avec sa référence exacte. */
data class LibraryPassage(val reference: String, val excerpt: String, val verified: Boolean)

/** Recherche dans la bibliothèque personnelle (comics, PDF, cours) — implémentée par forge-documents. */
fun interface LibraryTool {
    suspend fun search(query: String): List<LibraryPassage>
}

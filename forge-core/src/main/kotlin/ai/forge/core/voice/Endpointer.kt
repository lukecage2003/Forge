package ai.forge.core.voice

import ai.forge.core.text.TextUtils

/**
 * Détection de fin de tour de parole (§2.2). Le moteur de reconnaissance fournit des
 * transcriptions partielles ; l'endpointer décide quand l'utilisateur a réellement fini.
 *
 * Principe : le délai de silence requis s'allonge si la phrase semble inachevée
 * (hésitation « euh », connecteur final « et », « parce que », virgule…), et raccourcit
 * si elle se termine clairement (question, ponctuation forte).
 */
class Endpointer(
    private val baseSilenceMs: Long = 700,
    private val hesitationSilenceMs: Long = 1_800,
    private val decisiveSilenceMs: Long = 450,
) {
    private val hesitations = setOf("euh", "heu", "hum", "hmm", "bah", "ben", "bref")
    private val danglingWords = setOf(
        "et", "mais", "ou", "donc", "car", "parce", "que", "qui", "le", "la", "les", "un", "une", "des", "de",
        "du", "a", "au", "en", "pour", "avec", "sur", "dans", "comme", "si", "quand", "je", "tu", "il", "on",
        "puis", "alors", "enfin", "genre", "tipe",
    )

    enum class Ending { DECISIVE, NEUTRAL, INCOMPLETE }

    fun classify(partial: String): Ending {
        val trimmed = partial.trimEnd()
        if (trimmed.isEmpty()) return Ending.INCOMPLETE
        if (trimmed.endsWith(",") || trimmed.endsWith("...") || trimmed.endsWith("…")) return Ending.INCOMPLETE
        val last = TextUtils.tokens(trimmed).lastOrNull() ?: return Ending.INCOMPLETE
        if (last in hesitations || last in danglingWords) return Ending.INCOMPLETE
        if (trimmed.endsWith("?") || trimmed.endsWith("!") || trimmed.endsWith(".")) return Ending.DECISIVE
        return Ending.NEUTRAL
    }

    fun requiredSilenceMs(partial: String): Long = when (classify(partial)) {
        Ending.DECISIVE -> decisiveSilenceMs
        Ending.NEUTRAL -> baseSilenceMs
        Ending.INCOMPLETE -> hesitationSilenceMs
    }

    fun isEndOfTurn(partial: String, silenceMs: Long): Boolean =
        partial.isNotBlank() && silenceMs >= requiredSilenceMs(partial)
}

package ai.forge.core.voice

import ai.forge.core.text.TextUtils

/** Interprétation de ce que dit l'utilisateur pendant que Forge parle (§2.2). */
sealed interface BargeIn {
    /** « Attends », « stop », « laisse-moi terminer » : se taire et écouter. */
    data object StopAndListen : BargeIn
    /** « Changeons de sujet » : abandonner le fil courant. */
    data class ChangeTopic(val remainder: String) : BargeIn
    /** Arrêt complet de la session vocale. */
    data object EndSession : BargeIn
    /** Nouvelle prise de parole : interrompre et traiter ce nouvel énoncé. */
    data class NewUtterance(val text: String) : BargeIn
    /** Bruit / acquiescement (« oui », « hmm ») : ne pas interrompre. */
    data object Backchannel : BargeIn
}

class BargeInClassifier {
    private val stop = Regex("^(attends|attend|stop|arrete|tais[- ]toi|chut|une seconde|pause|laisse[- ]moi (finir|terminer|parler)|deux secondes|minute)\\b")
    private val endSession = Regex("\\b(fin de (la )?session|au revoir forge|arrete d'ecouter|coupe le micro|merci forge c'est tout)\\b")
    private val topic = Regex("^(en fait,?\\s*)?(changeons de sujet|change(ons)? de sujet|parlons d'autre chose|autre sujet|oublie ca)[,.]?\\s*(?<rest>.*)$")
    private val backchannel = setOf("oui", "ouais", "hmm", "hum", "mmh", "ok", "d'accord", "dac", "ah", "oh", "je vois", "exact", "voila")

    fun classify(utterance: String): BargeIn {
        val t = TextUtils.fold(utterance).trim().trimEnd('.', '!', '?')
        if (t.isEmpty()) return BargeIn.Backchannel
        if (endSession.containsMatchIn(t)) return BargeIn.EndSession
        if (stop.containsMatchIn(t)) return BargeIn.StopAndListen
        topic.find(t)?.let { return BargeIn.ChangeTopic(it.groups["rest"]!!.value.trim()) }
        if (t in backchannel) return BargeIn.Backchannel
        return BargeIn.NewUtterance(utterance.trim())
    }
}

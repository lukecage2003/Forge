package ai.forge.voice

import ai.forge.core.text.TextUtils

/**
 * Décisions face aux erreurs de la reconnaissance vocale Android (codes SpeechRecognizer).
 * Objectif : ne jamais afficher d'erreur pour un incident bénin (silence, fin de session, serveur
 * relancé), réessayer avec un délai croissant, basculer du moteur hors-ligne vers le moteur
 * standard si le pack de langue manque, et n'alerter l'utilisateur qu'en cas de panne durable.
 */
object RecognitionPolicy {
    // Valeurs des constantes android.speech.SpeechRecognizer.ERROR_*.
    const val NETWORK_TIMEOUT = 1; const val NETWORK = 2; const val AUDIO = 3; const val SERVER = 4; const val CLIENT = 5
    const val SPEECH_TIMEOUT = 6; const val NO_MATCH = 7; const val BUSY = 8; const val PERMISSIONS = 9; const val TOO_MANY_REQUESTS = 10
    const val SERVER_DISCONNECTED = 11; const val LANGUAGE_NOT_SUPPORTED = 12; const val LANGUAGE_UNAVAILABLE = 13

    sealed interface Action {
        /** Relancer l'écoute après [delayMs], sans rien afficher. */
        data class Retry(val delayMs: Long) : Action
        /** Détruire et recréer le moteur (état interne corrompu), puis réécouter. */
        data class Recreate(val delayMs: Long) : Action
        /** Le moteur hors-ligne ne gère pas le français ici : passer au moteur standard. */
        data object SwitchToStandardEngine : Action
        /** Panne durable : arrêter et expliquer à l'utilisateur. */
        data class Stop(val message: String) : Action
    }

    /** [consecutive] = nombre d'erreurs d'affilée (hors silences), remis à zéro après un résultat. */
    fun decide(error: Int, consecutive: Int, onDeviceEngine: Boolean): Action {
        val backoff = (300L * (1 shl consecutive.coerceAtMost(5))).coerceAtMost(8_000)
        return when (error) {
            NO_MATCH, SPEECH_TIMEOUT -> Action.Retry(150)                        // silence : normal
            CLIENT, BUSY -> if (consecutive >= 3) Action.Recreate(backoff) else Action.Retry(backoff)
            SERVER, SERVER_DISCONNECTED, TOO_MANY_REQUESTS -> if (consecutive >= 4) Action.Recreate(backoff) else Action.Retry(backoff)
            LANGUAGE_NOT_SUPPORTED, LANGUAGE_UNAVAILABLE ->
                if (onDeviceEngine) Action.SwitchToStandardEngine
                else Action.Stop("La reconnaissance vocale française n'est pas installée. Paramètres → Gestion générale → Reconnaissance vocale : ajoutez le français.")
            PERMISSIONS -> Action.Stop("Forge n'a pas accès au micro. Autorisez-le dans les paramètres de l'application.")
            AUDIO -> if (consecutive >= 3) Action.Stop("Le micro est utilisé par une autre application.") else Action.Retry(backoff)
            NETWORK, NETWORK_TIMEOUT ->
                if (onDeviceEngine) Action.Retry(backoff)
                else if (consecutive >= 3) Action.Stop("Reconnaissance vocale indisponible sans réseau. Installez le pack français hors-ligne dans les paramètres du téléphone.")
                else Action.Retry(backoff)
            else -> if (consecutive >= 5) Action.Stop("La reconnaissance vocale rencontre un problème (code $error). Réessayez dans un instant.") else Action.Retry(backoff)
        }
    }
}

/**
 * Évite qu'un même énoncé soit traité deux fois : la fin de phrase peut être détectée par Forge
 * (silence) PUIS confirmée par Android (onResults). Seul le premier compte.
 */
class UtteranceDeduplicator(private val windowMs: Long = 2_500, private val now: () -> Long = System::currentTimeMillis) {
    private var last: String? = null
    private var at = 0L
    fun accept(text: String): Boolean {
        val norm = TextUtils.fold(text).replace(Regex("[^a-z0-9]+"), " ").trim()
        if (norm.isEmpty()) return false
        val t = now()
        val dup = last != null && t - at < windowMs && (norm == last || norm.startsWith(last!!) || last!!.startsWith(norm))
        last = norm; at = t
        return !dup
    }
}

/**
 * Semi-duplex : pendant que Forge parle, le micro est coupé sauf si des écouteurs sont branchés
 * (le haut-parleur serait sinon capté et retranscrit). L'interruption reste possible par le bouton.
 */
object DuplexPolicy {
    fun listenWhileSpeaking(headsetConnected: Boolean, userEnabledBargeIn: Boolean) = headsetConnected || userEnabledBargeIn
}

/**
 * Mot d'appel « Forge » : en mode veille mains libres, seuls les énoncés qui l'interpellent sont
 * traités. Tolère les transcriptions approximatives (« forje », « forche », « for je »…).
 */
object WakeWord {
    private val variants = Regex("^(?:hey |ok |dis |eh |salut )?(forge|forj|forje|forges|forche|for je|fortch|fordge|phorge|forgue)\\b[ ,.!?:]*", RegexOption.IGNORE_CASE)

    /** Renvoie la demande sans le mot d'appel, ou null si Forge n'est pas interpellé. */
    fun strip(utterance: String): String? {
        val t = utterance.trim()
        val m = variants.find(TextUtils.fold(t)) ?: return null
        return t.substring(m.range.last + 1).trim().ifEmpty { "" }
    }
}

/**
 * Correction des noms propres et termes techniques mal transcrits (« tchala » → « T'Challa »).
 * Glossaire comics intégré + termes appris : quand l'utilisateur corrige un mot, il est ajouté.
 */
class Vocabulary(learned: Map<String, String> = emptyMap()) {
    private val entries = mutableMapOf<String, String>()   // forme fautive normalisée → forme correcte
    private val canonical = mutableSetOf<String>()

    init {
        BUILTIN.forEach { (right, wrongs) -> canonical += right; wrongs.forEach { entries[norm(it)] = right } }
        learned.forEach { (w, r) -> learn(w, r) }
    }

    fun learn(heard: String, correct: String) { entries[norm(heard)] = correct; canonical += correct }
    fun learnedPairs(): Map<String, String> = entries.toMap()

    fun correct(text: String): String {
        var out = text
        // Expressions de plusieurs mots d'abord (les plus longues gagnent).
        entries.keys.sortedByDescending { it.length }.forEach { wrong ->
            val rx = Regex("(?i)(?<![\\p{L}])" + wrong.split(' ').joinToString("[\\s'’-]*") { Regex.escape(it) } + "(?![\\p{L}])")
            out = rx.replace(out) { entries.getValue(wrong) }
        }
        return out
    }

    private fun norm(s: String) = TextUtils.fold(s).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    companion object {
        /** Forme correcte → transcriptions fautives fréquentes en français. */
        val BUILTIN: Map<String, List<String>> = mapOf(
            "T'Challa" to listOf("tchala", "tchalla", "t chala", "téchala", "tea challa", "t'chala"),
            "Wakanda" to listOf("wakhanda", "wa kanda", "ouakanda"),
            "vibranium" to listOf("vibra nium", "vibranium m", "vibrannium"),
            "Black Panther" to listOf("black panter", "blackpanther", "black panthère", "blaque panther"),
            "Galactus" to listOf("galactuce", "galactus s", "gala tus"),
            "Thanos" to listOf("tanos", "tannos"),
            "Darkseid" to listOf("dark side", "darkside", "dark seid"),
            "Doctor Doom" to listOf("docteur dum", "doctor dum"),
            "Fatalis" to listOf("fatalisse", "fatali"),
            "Killmonger" to listOf("kill monger", "kilmonger"),
            "Shuri" to listOf("shury", "chouri"),
            "Okoye" to listOf("okoyé", "o koye"),
            "Dora Milaje" to listOf("dora milajé", "dora milage", "dora mila je"),
            "Bast" to listOf("baste"),
            "Mjolnir" to listOf("miolnir", "mjölnir", "mjol nir"),
            "Asgard" to listOf("as gard"),
            "Spider-Man" to listOf("spiderman", "spider man", "spaïdeur man"),
            "Wolverine" to listOf("wolverin", "wolvérine"),
            "X-Men" to listOf("x men", "ix men", "iks men"),
            "Batman" to listOf("bat man"),
            "Superman" to listOf("super man"),
            "Krypton" to listOf("kripton"),
            "Gotham" to listOf("gotam", "gotame"),
            "Primestone Media" to listOf("prime stone media", "primestone médias", "prime stone médias", "primstone media"),
            "Forge" to listOf("forje", "forche"),
            "Marvel" to listOf("marvelle"),
            "DC Comics" to listOf("dc comic", "décé comics", "dé cé comics"),
        )
    }
}

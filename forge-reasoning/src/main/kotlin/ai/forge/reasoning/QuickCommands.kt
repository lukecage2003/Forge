package ai.forge.reasoning

import ai.forge.core.text.TextUtils
import ai.forge.security.ToolCall
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Commandes « Jarvis » reconnues instantanément, sans passer par le modèle (latence quasi nulle,
 * fonctionne hors-ligne) : ouvrir une application, minuteur, lampe, musique, itinéraire.
 * Chaque commande reste un simple appel d'outil soumis au portail d'autorisation.
 */
object QuickCommands {
    private const val LEAD = "^(?:(?:ok|he|hey|dis)\\s+)?(?:forge[,!.\\s]+)?(?:(?:tu\\s+peux|peux[- ]tu|est-ce que tu peux)\\s+)?(?:me\\s+|m')?"

    private val open = Regex("$LEAD(?:ouvre|ouvrir|lance|lancer|demarre|demarrer|affiche|va sur|montre)(?:[- ]moi)?\\s+(?:l'application\\s+|l'appli\\s+|l'app\\s+|mon\\s+|ma\\s+|mes\\s+|le\\s+|la\\s+|les\\s+|l')?(.{2,40}?)[\\s.!?]*$")
    private val timer = Regex("(?:minuteur|timer|compte a rebours|chrono)\\D{0,25}?(\\d{1,3})\\s*(secondes?|sec|s|minutes?|min|mn|heures?|h)\\b")
    private val timer2 = Regex("(\\d{1,3})\\s*(secondes?|minutes?|min|heures?)\\s+(?:de\\s+)?(?:minuteur|timer|compte a rebours)")
    private val torch = Regex("\\b(allume|active|eteins|eteint|desactive|coupe)\\s+(?:la\\s+|le\\s+)?(lampe(?: torche)?|torche|flash)\\b")
    private val play = Regex("$LEAD(?:mets|joue|lance|ecouter|fais[- ]moi ecouter)\\s+(?:moi\\s+)?(.{2,60}?)(?:\\s+sur\\s+(spotify|youtube music|deezer))?[\\s.!?]*$")
    private val musicWords = Regex("\\b(musique|chanson|son|titre|album|playlist|morceau|spotify|deezer|rap|radio)\\b")
    private val navigate = Regex("(?:itineraire|emmene[- ]moi|guide[- ]moi|conduis[- ]moi|navigation|route)\\s+(?:vers|a|jusqu'a|pour aller a|au|aux|chez)\\s+(.{2,60}?)[\\s.!?]*$")

    fun parse(utterance: String): ToolCall? {
        val raw = utterance.trim()
        val t = TextUtils.fold(raw).replace('’', '\'')
        val why = "Commande : « $raw »"

        timer.find(t)?.let { return timerCall(it.groupValues[1], it.groupValues[2], why) }
        timer2.find(t)?.let { return timerCall(it.groupValues[1], it.groupValues[2], why) }
        torch.find(t)?.let { m ->
            val on = m.groupValues[1] in setOf("allume", "active")
            return ToolCall("android.torch", JsonObject(mapOf("on" to JsonPrimitive(on))), why)
        }
        navigate.find(t)?.let { m -> return ToolCall("android.navigate", JsonObject(mapOf("destination" to JsonPrimitive(original(raw, m.groupValues[1])))), why) }
        play.find(t)?.takeIf { musicWords.containsMatchIn(t) && it.groupValues[1].trim() !in MUSIC_APPS }?.let { m ->
            val q = m.groupValues[1].replace(Regex("^(de la |la |du |un |une |le |les |ma |mes |mon )?(musique|chanson|son|playlist|morceau)( de| du| des)?\\s*"), "").trim()
            return ToolCall("android.play_music", JsonObject(mapOf("query" to JsonPrimitive(original(raw, q)))), why)
        }
        open.find(t)?.let { m ->
            val name = m.groupValues[1].trim()
            if (name.split(' ').size <= 4 && name !in setOf("moi", "ca", "cela", "toi")) return ToolCall("android.open_app", JsonObject(mapOf("name" to JsonPrimitive(original(raw, name)))), why)
        }
        return null
    }

    private val MUSIC_APPS = setOf("spotify", "deezer", "youtube music", "apple music", "soundcloud")

    private fun timerCall(n: String, unit: String, why: String): ToolCall {
        val k = when { unit.startsWith("s") -> 1; unit.startsWith("h") -> 3600; else -> 60 }
        return ToolCall("android.timer", JsonObject(mapOf("seconds" to JsonPrimitive(n.toInt() * k))), why)
    }

    /** Retrouve la casse et les accents d'origine du fragment (pour l'afficher et le chercher). */
    private fun original(raw: String, folded: String): String {
        if (folded.isEmpty()) return folded
        val f = TextUtils.fold(raw).replace('’', '\'')
        val i = f.indexOf(folded)
        return if (i >= 0 && f.length == raw.length) raw.substring(i, i + folded.length) else folded
    }
}

package ai.forge.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Détection du mot d'appel « Forge » à partir des résultats d'un moteur hors-ligne à grammaire
 * restreinte (Vosk). La grammaire contient des mots « leurres » proches (« forger », « forges »,
 * « forte »…) : s'ils sont reconnus, ce n'est pas un appel. Seuil de confiance réglable et délai
 * minimal entre deux déclenchements.
 */
class WakeWordSpotter(
    /** 0 = très sensible, 1 = très strict. */
    var strictness: Double = 0.55,
    private val cooldownMs: Long = 2_000,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var lastTrigger = -1_000_000L

    /** Résultat final (avec confiances par mot) : vrai si « Forge » est appelé. */
    fun onResult(resultJson: String): Boolean {
        val obj = runCatching { json.parseToJsonElement(resultJson).jsonObject }.getOrNull() ?: return false
        val words = obj["result"]?.jsonArray?.mapNotNull { w ->
            val o = w.jsonObject
            val word = o["word"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            word to (o["conf"]?.jsonPrimitive?.doubleOrNull ?: 1.0)
        } ?: obj["text"]?.jsonPrimitive?.contentOrNull?.split(" ")?.filter { it.isNotBlank() }?.map { it to 1.0 }.orEmpty()
        val hit = words.any { (w, c) -> w == WORD && c >= strictness }
        if (!hit) return false
        val t = now()
        if (t - lastTrigger < cooldownMs) return false
        lastTrigger = t
        return true
    }

    /**
     * Résultat partiel (sans confiance) : déclenchement immédiat seulement si « forge » est le
     * premier mot entendu — on gagne une demi-seconde, sans attendre la fin de la phrase.
     */
    fun onPartial(partialJson: String): Boolean {
        val text = runCatching { json.parseToJsonElement(partialJson).jsonObject["partial"]?.jsonPrimitive?.contentOrNull }.getOrNull() ?: return false
        if (strictness > 0.75 || text.trim().split(" ").firstOrNull() != WORD) return false
        val t = now()
        if (t - lastTrigger < cooldownMs) return false
        lastTrigger = t
        return true
    }

    companion object {
        const val WORD = "forge"
        /** Grammaire Vosk : le mot d'appel, des leurres proches, et « [unk] » pour tout le reste. */
        val GRAMMAR = """["forge", "forger", "forges", "forgé", "forte", "fort", "porte", "[unk]"]"""
    }
}

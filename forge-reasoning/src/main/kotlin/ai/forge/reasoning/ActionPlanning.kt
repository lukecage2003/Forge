package ai.forge.reasoning

import ai.forge.core.text.TextUtils
import ai.forge.models.ChatRequest
import ai.forge.models.LlmProvider
import ai.forge.models.complete
import ai.forge.core.model.ChatMessage
import ai.forge.core.model.Role
import ai.forge.security.ToolCall
import ai.forge.security.ToolSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Résultat réel d'une action, tel que rapporté par le moteur d'actions. */
data class ActionOutcome(val status: Status, val detail: String) {
    enum class Status { DONE, NEEDS_CONFIRMATION, DENIED, FAILED, BLOCKED }
}

/** Exécuteur d'actions (implémenté par forge-actions/ActionEngine côté application). */
fun interface ToolCallExecutor {
    /** [confirmed] = l'utilisateur vient de confirmer explicitement CET appel. */
    suspend fun execute(call: ToolCall, confirmed: Boolean): ActionOutcome
}

/**
 * Transforme une demande en appel d'outil.
 * 1. Analyse déterministe des demandes courantes (fonctionne hors-ligne, même avec un petit modèle).
 * 2. Sinon, le modèle choisit un outil parmi ceux AUTORISÉS, au format JSON strict.
 * Le modèle ne fait que proposer : l'exécution passe toujours par le portail d'autorisation.
 */
class ActionPlanner(private val tools: () -> List<ToolSpec>) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // « mets un réveil à 7h30 », « réveille-moi à 6 h », « alarme à 22:15 pour la vidéo »
    private val alarm = Regex("(reveil|alarme|reveille[- ]moi)\\D{0,20}?(\\d{1,2})\\s*(?:h|:|heures?)\\s*(\\d{2})?(?:\\s*(?:pour|:)\\s*(.+))?")

    fun quickParse(utterance: String): ToolCall? {
        val t = TextUtils.fold(utterance)
        if (tools().none { it.name == "android.alarm" }) return null
        alarm.find(t)?.let { m ->
            val h = m.groupValues[2].toInt(); val min = m.groupValues[3].ifEmpty { "0" }.toInt()
            if (h !in 0..23 || min !in 0..59) return null
            val args = buildMap<String, JsonPrimitive> {
                put("hour", JsonPrimitive(h)); put("minute", JsonPrimitive(min))
                m.groupValues[4].trim().takeIf { it.isNotEmpty() }?.let { put("label", JsonPrimitive(it)) }
            }
            return ToolCall("android.alarm", JsonObject(args), "Demande : « ${utterance.trim()} »")
        }
        return null
    }

    fun prompt(utterance: String): String = buildString {
        appendLine("Tu traduis une demande en UN appel d'outil. Outils disponibles :")
        tools().forEach { appendLine("- ${it.name} : ${it.description}") }
        appendLine("""Réponds UNIQUEMENT par un objet JSON, sans texte autour :
            |{"tool": "<nom>", "arguments": {...}, "rationale": "<pourquoi>"}
            |ou, si aucun outil ne convient ou s'il manque une information :
            |{"tool": null, "question": "<question à poser à l'utilisateur>"}""".trimMargin())
        appendLine("Demande : $utterance")
    }

    sealed interface Plan {
        data class Call(val call: ToolCall) : Plan
        data class Clarify(val question: String) : Plan
        data class Unparseable(val raw: String) : Plan
    }

    fun parse(raw: String): Plan {
        val start = raw.indexOf('{'); val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return Plan.Unparseable(raw)
        val obj = runCatching { json.parseToJsonElement(raw.substring(start, end + 1)).jsonObject }.getOrNull() ?: return Plan.Unparseable(raw)
        val tool = (obj["tool"] as? JsonPrimitive)?.contentOrNull
        if (tool == null) return Plan.Clarify(obj["question"]?.jsonPrimitive?.contentOrNull ?: "Pouvez-vous préciser l'action souhaitée ?")
        if (tools().none { it.name == tool }) return Plan.Clarify("Je ne dispose pas de l'outil « $tool ». Que souhaitez-vous faire exactement ?")
        val args = obj["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        val why = obj["rationale"]?.jsonPrimitive?.contentOrNull ?: "proposé par le modèle"
        return Plan.Call(ToolCall(tool, args, why))
    }

    suspend fun plan(utterance: String, provider: LlmProvider?): Plan {
        quickParse(utterance)?.let { return Plan.Call(it) }
        provider ?: return Plan.Clarify("Je n'ai pas de moteur disponible pour interpréter cette action ; formulez-la plus simplement.")
        val raw = provider.complete(ChatRequest(listOf(ChatMessage(Role.USER, prompt(utterance))), temperature = 0.0, maxTokens = 300))
            .getOrElse { return Plan.Unparseable(it.message ?: "") }
        return parse(raw)
    }

    companion object {
        /** Message à l'utilisateur construit à partir du résultat RÉEL, jamais rédigé par le modèle. */
        fun report(call: ToolCall, outcome: ActionOutcome): String = when (outcome.status) {
            ActionOutcome.Status.DONE -> "C'est transmis : ${outcome.detail}"
            ActionOutcome.Status.NEEDS_CONFIRMATION -> "Je n'ai rien exécuté. ${outcome.detail} Confirmez-vous « ${call.tool} » ?"
            ActionOutcome.Status.DENIED -> "Je ne peux pas le faire : ${outcome.detail}"
            ActionOutcome.Status.FAILED -> "L'action a échoué : ${outcome.detail}"
            ActionOutcome.Status.BLOCKED -> "Arrêt d'urgence actif : aucune action n'est lancée."
        }
    }
}

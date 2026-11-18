package ai.forge.models

import ai.forge.core.model.ChatMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.fold

/** Où s'exécute un moteur : la politique local-first en dépend (§1.1). */
enum class Locality { ON_DEVICE, LOCAL_NETWORK, CLOUD }

data class ProviderCapabilities(
    val locality: Locality,
    val contextTokens: Int,
    val vision: Boolean = false,
    val toolUse: Boolean = false,
    /** Qualité de raisonnement estimée, 1 (faible) à 5 (excellente) — à recalibrer par Forge Brain. */
    val reasoningQuality: Int = 2,
    /** Coût relatif par requête, 0 = gratuit. */
    val relativeCost: Int = 0,
)

data class ChatRequest(
    val messages: List<ChatMessage>,
    val temperature: Double = 0.7,
    val maxTokens: Int = 1024,
    val stop: List<String> = emptyList(),
)

/** Événement de génération en flux continu. */
sealed interface LlmEvent {
    data class Token(val text: String) : LlmEvent
    data class Done(val finishReason: String?) : LlmEvent
    data class Failure(val message: String, val cause: Throwable? = null) : LlmEvent
}

/**
 * Contrat commun à tous les moteurs (local, réseau local, cloud).
 * Le flux DOIT être annulable : l'annulation de la coroutine collectrice
 * interrompt la génération (indispensable pour l'interruption vocale).
 */
interface LlmProvider {
    val id: String
    val displayName: String
    val capabilities: ProviderCapabilities
    /** Vérification rapide, appelée avant chaque requête (peut s'appuyer sur un cache). */
    suspend fun isAvailable(): Boolean
    /** Test réel de connexion (§14 « tester une connexion ») : effectue un appel au service. */
    suspend fun testConnection(): Result<String> =
        if (isAvailable()) Result.success("disponible") else Result.failure(IllegalStateException("indisponible"))
    fun stream(request: ChatRequest): Flow<LlmEvent>
}

/** Collecte complète d'une réponse (usage non vocal). */
suspend fun LlmProvider.complete(request: ChatRequest): Result<String> {
    var failure: LlmEvent.Failure? = null
    val text = stream(request).fold(StringBuilder()) { acc, e ->
        when (e) {
            is LlmEvent.Token -> acc.append(e.text)
            is LlmEvent.Failure -> failure = e
            is LlmEvent.Done -> Unit
        }
        acc
    }.toString()
    return failure?.let { Result.failure(IllegalStateException(it.message, it.cause)) } ?: Result.success(text)
}

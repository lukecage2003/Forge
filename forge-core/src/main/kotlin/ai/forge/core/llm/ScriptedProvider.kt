package ai.forge.core.llm

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Moteur déterministe pour les tests et les démonstrations hors-ligne.
 * Il ne « réfléchit » pas : il renvoie la réponse produite par [responder].
 * Ne jamais le présenter à l'utilisateur comme une intelligence réelle.
 */
class ScriptedProvider(
    override val id: String = "scripted",
    override val displayName: String = "Moteur de test (scripté)",
    override val capabilities: ProviderCapabilities = ProviderCapabilities(Locality.ON_DEVICE, 32_000),
    private val tokenDelayMs: Long = 0,
    private val responder: (ChatRequest) -> String,
) : LlmProvider {
    val requests = mutableListOf<ChatRequest>()
    override suspend fun isAvailable() = true
    override fun stream(request: ChatRequest): Flow<LlmEvent> = flow {
        requests += request
        responder(request).split(Regex("(?<= )")).forEach {
            if (tokenDelayMs > 0) delay(tokenDelayMs)
            emit(LlmEvent.Token(it))
        }
        emit(LlmEvent.Done("stop"))
    }
}

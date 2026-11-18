package ai.forge.app.llm

import ai.forge.core.llm.ChatRequest
import ai.forge.core.llm.LlmEvent
import ai.forge.core.llm.LlmProvider
import ai.forge.core.llm.Locality
import ai.forge.core.llm.ProviderCapabilities
import ai.forge.core.model.Role
import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import java.io.File

/**
 * Moteur 100 % local sur le téléphone via MediaPipe LLM Inference (GPU du Snapdragon 8 Elite).
 * L'utilisateur fournit un modèle au format .task (ex. Gemma 3 4B int4) ; aucun appel réseau.
 *
 * Limites : un seul appel à la fois ; l'annulation coupe la diffusion côté Forge mais le moteur
 * peut terminer la génération en arrière-plan ; le gabarit de chat est celui de Gemma.
 */
class MediaPipeLocalProvider(
    private val context: Context,
    private val modelPath: String,
    private val maxTokens: Int = 4096,
    override val id: String = "local-mediapipe",
    override val displayName: String = "Modèle local (sur l'appareil)",
) : LlmProvider {
    override val capabilities = ProviderCapabilities(Locality.ON_DEVICE, contextTokens = maxTokens, reasoningQuality = 2)

    private var engine: LlmInference? = null

    override suspend fun isAvailable(): Boolean = File(modelPath).canRead()

    private fun engine(): LlmInference = engine ?: LlmInference.createFromOptions(
        context,
        LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .setMaxTokens(maxTokens)
            .build()
    ).also { engine = it }

    override fun stream(request: ChatRequest): Flow<LlmEvent> = callbackFlow {
        val prompt = gemmaPrompt(request)
        try {
            engine().generateResponseAsync(prompt) { partial, done ->
                if (!partial.isNullOrEmpty()) trySend(LlmEvent.Token(partial))
                if (done) { trySend(LlmEvent.Done("stop")); close() }
            }
        } catch (t: Throwable) {
            trySend(LlmEvent.Failure("Modèle local : ${t.message}", t)); close()
        }
        awaitClose { }
    }.flowOn(Dispatchers.Default)

    fun release() { engine?.close(); engine = null }

    private fun gemmaPrompt(r: ChatRequest): String = buildString {
        val system = r.messages.filter { it.role == Role.SYSTEM }.joinToString("\n\n") { it.content }
        var first = true
        r.messages.filter { it.role != Role.SYSTEM }.forEach { m ->
            val role = if (m.role == Role.ASSISTANT) "model" else "user"
            append("<start_of_turn>").append(role).append('\n')
            if (first && role == "user" && system.isNotBlank()) { append(system).append("\n\n"); first = false }
            append(m.content).append("<end_of_turn>\n")
        }
        append("<start_of_turn>model\n")
    }
}

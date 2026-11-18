package ai.forge.app.llm

import ai.forge.core.model.Role
import ai.forge.models.ChatRequest
import ai.forge.models.LlmEvent
import ai.forge.models.LlmProvider
import ai.forge.models.Locality
import ai.forge.models.ProviderCapabilities
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Moteur local LiteRT-LM (Google) — exécute Gemma 4 sur le GPU du Snapdragon 8 Elite.
 * - Le moteur (plusieurs Go) est chargé une seule fois, puis réutilisé ; [warmUp] le prépare
 *   en arrière-plan pour que la première réponse soit rapide.
 * - GPU en priorité ; si l'initialisation GPU échoue, repli automatique sur le CPU (signalé).
 * - L'annulation (interruption vocale) arrête réellement la génération (cancelProcess).
 */
class LiteRtLmProvider(
    private val modelPath: String,
    private val cacheDir: String,
    private val maxTokens: Int = 4_096,
    quality: Int = 3,
    override val id: String = "local-litertlm",
    override val displayName: String = "Gemma (sur le téléphone)",
) : LlmProvider {
    override val capabilities = ProviderCapabilities(Locality.ON_DEVICE, contextTokens = maxTokens, reasoningQuality = quality)

    /** Backend effectivement utilisé, pour l'affichage (« GPU » ou « CPU (repli) »). */
    val activeBackend = MutableStateFlow<String?>(null)
    private val lock = Mutex()
    @Volatile private var engine: Engine? = null

    override suspend fun isAvailable(): Boolean = File(modelPath).canRead()

    suspend fun warmUp(): Result<String> = runCatching { engine(); activeBackend.value ?: "?" }

    private suspend fun engine(): Engine = lock.withLock {
        engine?.let { return it }
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            val gpu = runCatching {
                Engine(EngineConfig(modelPath, Backend.GPU(), maxNumTokens = maxTokens, cacheDir = cacheDir)).also { it.initialize() }
            }
            val e = gpu.getOrNull()?.also { activeBackend.value = "GPU" }
                ?: Engine(EngineConfig(modelPath, Backend.CPU(), maxNumTokens = maxTokens, cacheDir = cacheDir)).also {
                    it.initialize(); activeBackend.value = "CPU (repli : ${gpu.exceptionOrNull()?.message?.take(60)})"
                }
            engine = e
            e
        }
    }

    override fun stream(request: ChatRequest): Flow<LlmEvent> = flow {
        val e = try { engine() } catch (t: Throwable) {
            emit(LlmEvent.Failure("Impossible de charger le modèle local : ${t.message}", t)); return@flow
        }
        val system = request.messages.filter { it.role == Role.SYSTEM }.joinToString("\n\n") { it.content }
        val turns = request.messages.filter { it.role != Role.SYSTEM }
        val last = turns.lastOrNull { it.role == Role.USER } ?: run { emit(LlmEvent.Done("vide")); return@flow }
        val history = turns.dropLast(1).map { m ->
            if (m.role == Role.ASSISTANT) Message.model(m.content) else Message.user(m.content)
        }
        val conversation = e.createConversation(ConversationConfig(
            systemInstruction = if (system.isBlank()) null else Contents.of(system),
            initialMessages = history,
            samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = request.temperature, seed = 0),
        ))
        try {
            var emitted = ""
            conversation.sendMessageAsync(last.content).collect { msg ->
                val text = msg.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                // Le moteur peut renvoyer des fragments ou le texte cumulé : on n'émet que le nouveau.
                val delta = if (text.startsWith(emitted) && emitted.isNotEmpty()) text.removePrefix(emitted) else text
                if (delta.isNotEmpty()) { emit(LlmEvent.Token(delta)); emitted = if (text.startsWith(emitted)) text else emitted + delta }
            }
            emit(LlmEvent.Done("stop"))
        } catch (c: kotlinx.coroutines.CancellationException) {
            runCatching { conversation.cancelProcess() }
            throw c
        } catch (t: Throwable) {
            emit(LlmEvent.Failure("Modèle local : ${t.message}", t))
        } finally {
            runCatching { conversation.close() }
        }
    }.flowOn(Dispatchers.IO)

    fun release() { runCatching { engine?.close() }; engine = null; activeBackend.value = null }
}

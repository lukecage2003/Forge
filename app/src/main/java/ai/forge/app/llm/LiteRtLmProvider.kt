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
    private val maxTokens: Int = 8_192,
    quality: Int = 3,
    override val id: String = "local-litertlm",
    override val displayName: String = "Gemma (sur le téléphone)",
) : LlmProvider {
    @Volatile private var contextTokens = maxTokens
    private val quality0 = quality
    /** Contexte réel après démarrage (8k si le GPU l'accepte, sinon 4k). */
    override val capabilities get() = ProviderCapabilities(Locality.ON_DEVICE, contextTokens = contextTokens, reasoningQuality = quality0)

    /** Backend effectivement utilisé, pour l'affichage (« GPU » ou « CPU (repli) »). */
    val activeBackend = MutableStateFlow<String?>(null)
    private val lock = Mutex()
    @Volatile private var engine: Engine? = null

    override suspend fun isAvailable(): Boolean = File(modelPath).canRead()

    suspend fun warmUp(): Result<String> = runCatching { engine(); activeBackend.value ?: "?" }

    private suspend fun engine(): Engine = lock.withLock {
        engine?.let { return it }
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            // Ordre d'essai : GPU grand contexte → GPU contexte standard → CPU. Le premier qui démarre gagne.
            val attempts = listOf<Pair<() -> Backend, Int>>({ Backend.GPU() } to maxTokens, { Backend.GPU() } to 4_096,
                { Backend.CPU() } to maxTokens, { Backend.CPU() } to 4_096).distinct()
            var lastError: Throwable? = null
            for ((backend, tokens) in attempts) {
                val b = backend()
                val e = runCatching { Engine(EngineConfig(modelPath, b, maxNumTokens = tokens, cacheDir = cacheDir)).also { it.initialize() } }
                    .onFailure { lastError = it }.getOrNull() ?: continue
                contextTokens = tokens
                activeBackend.value = (if (b is Backend.GPU) "GPU" else "CPU") + " · contexte ${tokens / 1024}k"
                engine = e
                return@withContext e
            }
            throw IllegalStateException("aucune configuration n'a démarré (${lastError?.message})", lastError)
        }
    }

    /**
     * Conversation gardée d'un tour à l'autre : si la nouvelle requête prolonge exactement la
     * précédente (mêmes instructions, mêmes réponses), seul le nouveau message est traité —
     * le moteur ne relit plus toute la conversation (gain majeur de latence).
     */
    private class Live(val conversation: com.google.ai.edge.litertlm.Conversation, val system: String, val shape: List<Pair<Role, String>>)
    @Volatile private var live: Live? = null

    private fun shapeOf(turns: List<ai.forge.core.model.ChatMessage>) =
        turns.map { it.role to if (it.role == Role.ASSISTANT) it.content.trim() else "" }

    override fun stream(request: ChatRequest): Flow<LlmEvent> = flow {
        val e = try { engine() } catch (t: Throwable) {
            emit(LlmEvent.Failure("Impossible de charger le modèle local : ${t.message}", t)); return@flow
        }
        val system = request.messages.filter { it.role == Role.SYSTEM }.joinToString("\n\n") { it.content }
        val turns = request.messages.filter { it.role != Role.SYSTEM }
        val last = turns.lastOrNull { it.role == Role.USER } ?: run { emit(LlmEvent.Done("vide")); return@flow }
        val history = turns.dropLast(1)
        val reusable = live?.takeIf { it.system == system && it.shape == shapeOf(history) && it.conversation.isAlive }
        val conversation = reusable?.conversation ?: run {
            live?.let { runCatching { it.conversation.close() } }; live = null
            e.createConversation(ConversationConfig(
                systemInstruction = if (system.isBlank()) null else Contents.of(system),
                initialMessages = history.map { m -> if (m.role == Role.ASSISTANT) Message.model(m.content) else Message.user(m.content) },
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = request.temperature, seed = 0),
            ))
        }
        var emitted = ""
        try {
            conversation.sendMessageAsync(last.content).collect { msg ->
                val text = msg.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                // Le moteur peut renvoyer des fragments ou le texte cumulé : on n'émet que le nouveau.
                val delta = if (text.startsWith(emitted) && emitted.isNotEmpty()) text.removePrefix(emitted) else text
                if (delta.isNotEmpty()) { emit(LlmEvent.Token(delta)); emitted = if (text.startsWith(emitted)) text else emitted + delta }
            }
            live = Live(conversation, system, shapeOf(history) + (Role.USER to "") + (Role.ASSISTANT to emitted.trim()))
            emit(LlmEvent.Done("stop"))
        } catch (c: kotlinx.coroutines.CancellationException) {
            runCatching { conversation.cancelProcess() }; runCatching { conversation.close() }; live = null
            throw c
        } catch (t: Throwable) {
            runCatching { conversation.close() }; live = null
            emit(LlmEvent.Failure("Modèle local : ${t.message}", t))
        }
    }.flowOn(Dispatchers.IO)

    fun release() { live?.let { runCatching { it.conversation.close() } }; live = null; runCatching { engine?.close() }; engine = null; activeBackend.value = null }
}

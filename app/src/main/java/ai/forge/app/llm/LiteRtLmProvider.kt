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
    /**
     * Repli sur le processeur si le GPU refuse. Désactivé pour les gros modèles : sur le CPU,
     * un modèle de 4 Go sature tous les cœurs et fait ramer tout le téléphone.
     */
    private val allowCpu: Boolean = true,
    override val id: String = "local-litertlm",
    override val displayName: String = "Gemma (sur le téléphone)",
) : LlmProvider {
    @Volatile private var contextTokens = maxTokens
    @Volatile private var visionEnabled = false
    private val quality0 = quality
    /** Contexte réel après démarrage (8k si le GPU l'accepte, sinon 4k). */
    override val capabilities get() = ProviderCapabilities(Locality.ON_DEVICE, contextTokens = contextTokens, vision = visionEnabled, reasoningQuality = quality0)

    /** Backend effectivement utilisé, pour l'affichage (« GPU » ou « CPU (repli) »). */
    val activeBackend = MutableStateFlow<String?>(null)
    private val lock = Mutex()
    @Volatile private var engine: Engine? = null

    override suspend fun isAvailable(): Boolean = File(modelPath).canRead()

    val isLoaded: Boolean get() = engine != null

    suspend fun warmUp(): Result<String> = runCatching { engine(); activeBackend.value ?: "?" }

    private suspend fun engine(): Engine = lock.withLock {
        engine?.let { return it }
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            // Ordre d'essai : GPU grand contexte → GPU contexte standard → CPU. Le premier qui démarre gagne.
            // Essais : vision activée d'abord (Gemma 4 voit les images), puis sans vision ; GPU puis CPU.
            data class Attempt(val gpu: Boolean, val vision: Boolean, val tokens: Int)
            // Contexte 4k : moins de mémoire, lecture plus rapide ; vision tentée puis abandonnée si refusée.
            val attempts = (listOf(Attempt(true, true, maxTokens), Attempt(true, false, maxTokens), Attempt(true, false, 2_048)) +
                if (allowCpu) listOf(Attempt(false, false, 2_048)) else emptyList()).distinct()
            var lastError: Throwable? = null
            for (a in attempts) {
                val b = if (a.gpu) Backend.GPU() else Backend.CPU()
                val config = if (a.vision) EngineConfig(modelPath, b, visionBackend = if (a.gpu) Backend.GPU() else Backend.CPU(), maxNumTokens = a.tokens, maxNumImages = 1, cacheDir = cacheDir)
                    else EngineConfig(modelPath, b, maxNumTokens = a.tokens, cacheDir = cacheDir)
                val e = runCatching { Engine(config).also { it.initialize() } }.onFailure { lastError = it }.getOrNull() ?: continue
                contextTokens = a.tokens; visionEnabled = a.vision
                activeBackend.value = (if (a.gpu) "GPU" else "CPU") + " · contexte ${a.tokens / 1024}k" + if (a.vision) " · vision" else ""
                engine = e
                return@withContext e
            }
            throw IllegalStateException(if (allowCpu) "aucune configuration n'a démarré (${lastError?.message})"
                else "le GPU n'a pas pu charger ce modèle (mémoire insuffisante ?). Fermez quelques applications ou installez Gemma 4 · E2B, plus léger.", lastError)
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

    /**
     * L'historique est « le même » si les rôles concordent et si chaque réponse gardée commence
     * par ce que le moteur a réellement généré (Forge y ajoute parfois les sources ou un contrôle).
     */
    private fun sameHistory(kept: List<Pair<Role, String>>, now: List<Pair<Role, String>>): Boolean =
        kept.size == now.size && kept.zip(now).all { (a, b) -> a.first == b.first && b.second.startsWith(a.second) }

    override fun stream(request: ChatRequest): Flow<LlmEvent> = flow {
        val e = try { engine() } catch (t: Throwable) {
            emit(LlmEvent.Failure("Impossible de charger le modèle local : ${t.message}", t)); return@flow
        }
        val system = request.messages.filter { it.role == Role.SYSTEM }.joinToString("\n\n") { it.content }
        val turns = request.messages.filter { it.role != Role.SYSTEM }
        val last = turns.lastOrNull { it.role == Role.USER } ?: run { emit(LlmEvent.Done("vide")); return@flow }
        val history = turns.dropLast(1)
        val reusable = live?.takeIf { it.system == system && sameHistory(it.shape, shapeOf(history)) && it.conversation.isAlive }
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
            val input = if (request.images.isNotEmpty() && visionEnabled)
                Contents.of(request.images.map { Content.ImageBytes(it) } + Content.Text(last.content)) else Contents.of(last.content)
            conversation.sendMessageAsync(input).collect { msg ->
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

package ai.forge.reasoning

import ai.forge.reasoning.agents.SpecialistId
import ai.forge.reasoning.agents.SpecialistSpec
import ai.forge.reasoning.agents.Specialists
import ai.forge.models.ChatRequest
import ai.forge.models.LlmEvent
import ai.forge.models.LlmProvider
import ai.forge.models.ProviderRouter
import ai.forge.models.RoutingNeeds
import ai.forge.models.complete
import ai.forge.memory.MemoryCandidateExtractor
import ai.forge.memory.MemoryManager
import ai.forge.memory.MemoryQuery
import ai.forge.memory.RememberOutcome
import ai.forge.core.model.ChatMessage
import ai.forge.core.model.Role
import ai.forge.core.model.ThinkingDepth
import ai.forge.core.persona.ForgePersona
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Mémoire immédiate : la conversation en cours, bornée. */
class ConversationContext(val id: String, private val maxTurns: Int = 24) {
    private val _turns = ArrayDeque<ChatMessage>()
    val turns: List<ChatMessage> get() = _turns.toList()
    var project: String? = null
    fun add(m: ChatMessage) { _turns.addLast(m); while (_turns.size > maxTurns) _turns.removeFirst() }
    /** Une réponse interrompue est conservée tronquée et marquée comme telle (contexte honnête). */
    fun addInterruptedAssistant(partial: String) {
        if (partial.isNotBlank()) add(ChatMessage(Role.ASSISTANT, "$partial […interrompu par l'utilisateur]"))
    }
}

sealed interface OrchestratorEvent {
    data class Planned(val intent: Intent, val provider: String, val steps: List<SpecialistId>) : OrchestratorEvent
    data class StepStarted(val specialist: SpecialistId) : OrchestratorEvent
    /** Fragment de la réponse finale (diffusé en continu vers l'écran ou la synthèse vocale). */
    data class Token(val text: String) : OrchestratorEvent
    data class QualityReview(val verdict: String, val issues: String, val revised: Boolean) : OrchestratorEvent
    data class MemoryEvent(val outcome: RememberOutcome) : OrchestratorEvent
    data class Completed(val fullText: String) : OrchestratorEvent
    data class Failed(val reason: String) : OrchestratorEvent
}

/**
 * Forge Core — orchestrateur (§3.2).
 * Comprendre → contexte → réponse simple ou plan → spécialistes → contrôle → réponse → mémoire.
 *
 * Choix de conception : les étapes intermédiaires (brouillon, contrôle qualité) ne sont pas
 * diffusées ; seule la réponse finale l'est. En mode Instant/Standard, la réponse est diffusée
 * directement pour minimiser la latence vocale.
 */
class Orchestrator(
    private val router: ProviderRouter,
    private val memory: MemoryManager,
    private val analyzer: IntentAnalyzer = IntentAnalyzer(),
    private val extractor: MemoryCandidateExtractor = MemoryCandidateExtractor(),
) {
    fun handle(input: String, ctx: ConversationContext, voice: Boolean, forcedDepth: ThinkingDepth? = null): Flow<OrchestratorEvent> = flow {
        // 1. Mémoire : uniquement ce que l'utilisateur veut explicitement conserver.
        extractor.extract(input, ctx.id, ctx.project).forEach { emit(OrchestratorEvent.MemoryEvent(memory.remember(it))) }

        // 2. Intention et profondeur.
        val intent = analyzer.analyze(input).let { if (forcedDepth != null) it.copy(depth = forcedDepth, needsVerification = forcedDepth.verify) else it }
        val provider = router.select(RoutingNeeds(depth = intent.depth))
        if (provider == null) {
            emit(OrchestratorEvent.Failed("Aucun moteur de langage disponible. Configurez un modèle local ou une clé API dans les réglages."))
            return@flow
        }
        val primary = Specialists[intent.primary]
        val steps = buildList {
            if (intent.depth >= ThinkingDepth.DEEP_RESEARCH) addAll(intent.supporting)
            add(intent.primary)
            if (intent.needsVerification) add(SpecialistId.QUALITY_CONTROL)
        }
        emit(OrchestratorEvent.Planned(intent, provider.displayName, steps))

        // 3. Rappel mémoire ciblé par le spécialiste principal.
        val hits = if (primary.memoryKinds.isEmpty()) emptyList()
            else memory.recall(MemoryQuery(input, primary.memoryKinds, limit = 6))
        val memoryContext = memory.formatForPrompt(hits)

        ctx.add(ChatMessage(Role.USER, input))

        // 4. Spécialistes de soutien (mode approfondi) : notes de travail internes.
        val notes = StringBuilder()
        for (id in steps.filter { it != intent.primary && it != SpecialistId.QUALITY_CONTROL }) {
            emit(OrchestratorEvent.StepStarted(id))
            val spec = Specialists[id]
            val r = provider.complete(request(spec, ctx, voice = false, memoryContext,
                extra = "Fournis des notes de travail concises pour aider le spécialiste « ${primary.name} ». Ne rédige pas la réponse finale."))
            r.onSuccess { notes.appendLine("### Notes ${spec.name}\n$it") }
        }

        emit(OrchestratorEvent.StepStarted(intent.primary))
        val extra = notes.takeIf { it.isNotEmpty() }?.let { "Notes internes des autres spécialistes :\n$it" }

        if (!intent.needsVerification) {
            // Diffusion directe (latence minimale).
            val text = StringBuilder()
            var failed: String? = null
            provider.stream(request(primary, ctx, voice, memoryContext, extra)).collect { e ->
                when (e) {
                    is LlmEvent.Token -> { text.append(e.text); emit(OrchestratorEvent.Token(e.text)) }
                    is LlmEvent.Failure -> failed = e.message
                    is LlmEvent.Done -> Unit
                }
            }
            failed?.let { emit(OrchestratorEvent.Failed(it)); return@flow }
            ctx.add(ChatMessage(Role.ASSISTANT, text.toString()))
            emit(OrchestratorEvent.Completed(text.toString()))
            return@flow
        }

        // 5. Brouillon → contrôle qualité → révision éventuelle (une passe).
        val draft = provider.complete(request(primary, ctx, voice, memoryContext, extra)).getOrElse {
            emit(OrchestratorEvent.Failed(it.message ?: "Échec de génération")); return@flow
        }
        emit(OrchestratorEvent.StepStarted(SpecialistId.QUALITY_CONTROL))
        val qc = Specialists[SpecialistId.QUALITY_CONTROL]
        val review = provider.complete(ChatRequest(listOf(
            ChatMessage(Role.SYSTEM, qc.instructions),
            ChatMessage(Role.USER, "Demande :\n$input\n\nRéponse proposée :\n$draft"),
        ), temperature = qc.temperature, maxTokens = 400)).getOrDefault("VERDICT: OK\nPROBLEMES: contrôle indisponible")
        val (verdict, issues) = parseReview(review)

        val final = if (verdict == "REVISER") {
            provider.complete(request(primary, ctx, voice, memoryContext,
                extra = "Ta première version présentait ces problèmes :\n$issues\nProduis une version corrigée complète.\nPremière version :\n$draft"))
                .getOrDefault(draft)
        } else draft
        emit(OrchestratorEvent.QualityReview(verdict, issues, revised = verdict == "REVISER"))
        // Diffusion de la version finale par phrases (lisible par la synthèse vocale).
        final.split(Regex("(?<=[.!?…])\\s+")).forEach { emit(OrchestratorEvent.Token("$it ")) }
        ctx.add(ChatMessage(Role.ASSISTANT, final))
        emit(OrchestratorEvent.Completed(final))
    }

    private fun request(spec: SpecialistSpec, ctx: ConversationContext, voice: Boolean, memoryContext: String, extra: String?): ChatRequest {
        val system = ForgePersona.systemPrompt(voice, "Spécialiste actif : ${spec.name}. ${spec.mission}\n${spec.instructions}", memoryContext)
        val msgs = buildList {
            add(ChatMessage(Role.SYSTEM, system + (extra?.let { "\n\n$it" } ?: "")))
            addAll(ctx.turns)
        }
        return ChatRequest(msgs, temperature = spec.temperature, maxTokens = if (voice) 600 else 2_000)
    }

    internal fun parseReview(text: String): Pair<String, String> {
        val verdict = Regex("VERDICT\\s*:\\s*(OK|REVISER)", RegexOption.IGNORE_CASE).find(text)
            ?.groupValues?.get(1)?.uppercase() ?: "OK"
        val issues = Regex("PROBL[EÈ]MES\\s*:\\s*(.*)", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(text)?.groupValues?.get(1)?.trim()?.ifEmpty { null } ?: "aucun"
        return verdict to issues
    }
}

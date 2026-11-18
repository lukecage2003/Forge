package ai.forge.reasoning

import ai.forge.reasoning.agents.SpecialistId
import ai.forge.reasoning.agents.SpecialistSpec
import ai.forge.reasoning.agents.Specialists
import ai.forge.models.ChatRequest
import ai.forge.models.LlmEvent
import ai.forge.models.LlmProvider
import ai.forge.models.ProviderRouter
import ai.forge.models.RoutingNeeds
import ai.forge.models.Locality
import ai.forge.models.complete
import ai.forge.memory.MemoryCandidateExtractor
import ai.forge.memory.MemoryHit
import ai.forge.memory.MemoryKind
import ai.forge.memory.Provenance
import ai.forge.memory.MemoryManager
import ai.forge.memory.MemoryQuery
import ai.forge.memory.RememberOutcome
import ai.forge.core.model.ChatMessage
import ai.forge.core.model.DataClass
import ai.forge.core.model.TaskCategory
import ai.forge.core.model.Role
import ai.forge.core.model.ThinkingDepth
import ai.forge.core.persona.ForgePersona
import ai.forge.security.UntrustedContent
import ai.forge.security.ToolCall
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
    /** Contenu externe contenant une tentative d'instruction : signalé, jamais exécuté. */
    data class SecurityWarning(val source: String, val findings: List<String>) : OrchestratorEvent
    data class ScriptReady(val script: ai.forge.editorial.ViralScript, val checks: List<ai.forge.editorial.RetentionCheck>, val revised: Boolean) : OrchestratorEvent
    data class LibraryResults(val passages: List<LibraryPassage>) : OrchestratorEvent
    data class ResearchDone(val sourceCount: Int, val bibliography: String, val refused: Int) : OrchestratorEvent
    data class ActionProposed(val call: ToolCall) : OrchestratorEvent
    data class ActionResult(val call: ToolCall, val outcome: ActionOutcome) : OrchestratorEvent
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
    /** Actions Android : null = aucune action possible (Forge le dit au lieu de faire semblant). */
    private val actionPlanner: ActionPlanner? = null,
    private val actionExecutor: ToolCallExecutor? = null,
    /** Recherche Web (null = indisponible : Forge le dit, il ne simule pas de recherche). */
    private val researchTool: ResearchTool? = null,
    /** Bibliothèque personnelle indexée (null = non configurée). */
    private val libraryTool: LibraryTool? = null,
    /** Analyse du marché YouTube (null = non configurée). */
    private val marketTool: MarketTool? = null,
) {
    fun handle(input: String, ctx: ConversationContext, voice: Boolean, forcedDepth: ThinkingDepth? = null): Flow<OrchestratorEvent> = flow {
        // 1. Mémoire : uniquement ce que l'utilisateur veut explicitement conserver.
        extractor.extract(input, ctx.id, ctx.project).forEach { emit(OrchestratorEvent.MemoryEvent(memory.remember(it))) }

        // 2. Intention et profondeur.
        val intent = analyzer.analyze(input).let { if (forcedDepth != null) it.copy(depth = forcedDepth, needsVerification = forcedDepth.verify) else it }
        val primary = Specialists[intent.primary]

        // Actions Android (§11) : planifier → portail d'autorisation → résultat réel.
        if (intent.primary == SpecialistId.ANDROID_ACTION) {
            ctx.add(ChatMessage(Role.USER, input))
            val text = handleAction(input, intent) { emit(it) }
            ctx.add(ChatMessage(Role.ASSISTANT, text))
            emit(OrchestratorEvent.Token(text))
            emit(OrchestratorEvent.Completed(text))
            return@flow
        }

        // 3. Rappel mémoire ciblé, AVANT le choix du moteur : le contenu décide de qui peut le recevoir.
        val hits = if (primary.memoryKinds.isEmpty()) emptyList()
            else memory.recall(MemoryQuery(input, primary.memoryKinds, limit = 6))
        val dataClass = classify(intent, hits)
        val (memoryContext, externalUsed) = buildMemoryContext(hits) { src, findings ->
            emit(OrchestratorEvent.SecurityWarning(src, findings))
        }

        val needs = RoutingNeeds(depth = intent.depth, task = taskOf(intent.primary), dataClass = dataClass)
        val provider = router.select(needs)
        if (provider == null) {
            emit(OrchestratorEvent.Failed("Aucun moteur disponible pour cette demande (${dataClass.label}). Détail : ${router.explain(needs)}"))
            return@flow
        }
        // Sur un modèle embarqué, chaque passe coûte plusieurs secondes : une seule passe diffusée
        // immédiatement, sauf là où le contrôle protège contre l'invention (comics, éditorial, recherche).
        val onDevice = provider.capabilities.locality == Locality.ON_DEVICE
        val verify = intent.needsVerification && (!onDevice || intent.primary in GUARDED)
        val steps = buildList {
            if (intent.depth >= ThinkingDepth.DEEP_RESEARCH && !onDevice) addAll(intent.supporting)
            add(intent.primary)
            if (verify) add(SpecialistId.QUALITY_CONTROL)
        }
        emit(OrchestratorEvent.Planned(intent, provider.displayName, steps))
        val securityRule = if (externalUsed) UntrustedContent.SYSTEM_RULE else null

        ctx.add(ChatMessage(Role.USER, input))

        // 3 bis. Recherche Web réelle pour les demandes de recherche.
        var bibliography: String? = null
        var researchNote: String? = null
        // Recherche automatique : demandes de recherche, mais aussi comics et éditorial (faits à vérifier).
        val wantsResearch = intent.primary in setOf(SpecialistId.RESEARCH, SpecialistId.COMICS, SpecialistId.EDITORIAL) ||
            (SpecialistId.RESEARCH in intent.supporting && intent.depth >= ThinkingDepth.DEEP_RESEARCH)
        if (wantsResearch && (researchTool != null || intent.primary == SpecialistId.RESEARCH)) {
            val tool = researchTool
            if (tool == null) researchNote = "Aucune recherche Web n'a pu être effectuée (outil indisponible ou hors-ligne) : réponds avec tes connaissances en le signalant clairement, sans inventer de source."
            else runCatching { tool.research(input) }.onSuccess { r ->
                emit(OrchestratorEvent.ResearchDone(r.sourceCount, r.bibliography, r.refusedCount))
                if (r.suspiciousCount > 0) emit(OrchestratorEvent.SecurityWarning("recherche Web", listOf("${r.suspiciousCount} page(s) contenant des tentatives d'instruction")))
                researchNote = r.promptContext
                bibliography = r.bibliography.takeIf { r.sourceCount > 0 }
            }.onFailure { researchNote = "La recherche Web a échoué (${it.message}) : signale-le et n'invente aucune source." }
        }

        // 3 ter. Bibliothèque personnelle (comics, documents) : passages localisés, jamais inventés.
        if (intent.primary == SpecialistId.COMICS || intent.primary == SpecialistId.DOCUMENT) {
            val lib = libraryTool
            val passages = lib?.let { runCatching { it.search(input) }.getOrDefault(emptyList()) }.orEmpty()
            if (lib != null) emit(OrchestratorEvent.LibraryResults(passages))
            val libNote = when {
                lib == null -> "La bibliothèque personnelle n'est pas indexée : ne cite aucune page ni aucun numéro comme s'ils venaient d'elle."
                passages.isEmpty() -> "Aucun passage correspondant dans la bibliothèque indexée : dis-le, et n'invente aucune référence."
                else -> buildString {
                    appendLine("Passages trouvés dans la bibliothèque (texte OCR, à citer avec leur référence exacte ; « non vérifié » = l'utilisateur doit consulter la page) :")
                    passages.forEachIndexed { i, p ->
                        appendLine(UntrustedContent.wrap(p.reference, "[B${i + 1}] ${p.reference}${if (p.verified) "" else " (non vérifié)"} : ${p.excerpt}").text)
                    }
                }
            }
            researchNote = listOfNotNull(researchNote, libNote).joinToString("\n\n")
            if (passages.isNotEmpty()) bibliography = listOfNotNull(bibliography, passages.mapIndexed { i, p ->
                "[B${i + 1}] ${p.reference} — ${if (p.verified) "vérifié" else "piste OCR, page à consulter"}" }.joinToString("\n")).joinToString("\n")
        }

        // Studio de scripts : format structuré, contrôle de rétention déterministe, une révision au besoin.
        if (intent.primary == SpecialistId.EDITORIAL) {
            val text = handleScript(input, provider, ctx, voice, memoryContext, researchNote, bibliography, securityRule) { emit(it) }
            if (text != null) { emit(OrchestratorEvent.Completed(text)); return@flow }
        }

        // 4. Spécialistes de soutien (mode approfondi) : notes de travail internes.
        val notes = StringBuilder()
        for (id in steps.filter { it != intent.primary && it != SpecialistId.QUALITY_CONTROL }) {
            emit(OrchestratorEvent.StepStarted(id))
            val spec = Specialists[id]
            val r = provider.complete(request(spec, ctx, voice = false, memoryContext,
                extra = listOfNotNull(researchNote, "Fournis des notes de travail concises pour aider le spécialiste « ${primary.name} ». Ne rédige pas la réponse finale.").joinToString("\n\n"), securityRule = securityRule))
            r.onSuccess { notes.appendLine("### Notes ${spec.name}\n$it") }
        }

        emit(OrchestratorEvent.StepStarted(intent.primary))
        val extra = listOfNotNull(researchNote, notes.takeIf { it.isNotEmpty() }?.let { "Notes internes des autres spécialistes :\n$it" })
            .joinToString("\n\n").ifEmpty { null }
        val sourcesFooter = bibliography?.let { if (voice) "" else "\n\nSources consultées :\n$it" }.orEmpty()

        if (!verify) {
            // Diffusion directe (latence minimale).
            val text = StringBuilder()
            var failed: String? = null
            provider.stream(request(primary, ctx, voice, memoryContext, extra, securityRule)).collect { e ->
                when (e) {
                    is LlmEvent.Token -> { text.append(e.text); emit(OrchestratorEvent.Token(e.text)) }
                    is LlmEvent.Failure -> failed = e.message
                    is LlmEvent.Done -> Unit
                }
            }
            failed?.let { emit(OrchestratorEvent.Failed(it)); return@flow }
            if (sourcesFooter.isNotEmpty()) { text.append(sourcesFooter); emit(OrchestratorEvent.Token(sourcesFooter)) }
            ctx.add(ChatMessage(Role.ASSISTANT, text.toString()))
            recordUsage(provider.id, ctx, text.toString())
            emit(OrchestratorEvent.Completed(text.toString()))
            return@flow
        }

        // 5. Brouillon → contrôle qualité → révision éventuelle (une passe).
        val draft = provider.complete(request(primary, ctx, voice, memoryContext, extra, securityRule)).getOrElse {
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
                extra = "Ta première version présentait ces problèmes :\n$issues\nProduis une version corrigée complète.\nPremière version :\n$draft", securityRule = securityRule))
                .getOrDefault(draft)
        } else draft
        val finalWithSources = final + sourcesFooter
        emit(OrchestratorEvent.QualityReview(verdict, issues, revised = verdict == "REVISER"))
        // Diffusion de la version finale par phrases (lisible par la synthèse vocale).
        finalWithSources.split(Regex("(?<=[.!?…])\\s+")).forEach { emit(OrchestratorEvent.Token("$it ")) }
        ctx.add(ChatMessage(Role.ASSISTANT, finalWithSources))
        recordUsage(provider.id, ctx, draft + review + final)
        emit(OrchestratorEvent.Completed(finalWithSources))
    }

    private fun request(spec: SpecialistSpec, ctx: ConversationContext, voice: Boolean, memoryContext: String, extra: String?, securityRule: String? = null): ChatRequest {
        val system = ForgePersona.systemPrompt(voice, "Spécialiste actif : ${spec.name}. ${spec.mission}\n${spec.instructions}", memoryContext)
        val msgs = buildList {
            add(ChatMessage(Role.SYSTEM, system + (securityRule?.let { "\n\n$it" } ?: "") + (extra?.let { "\n\n$it" } ?: "")))
            addAll(ctx.turns)
        }
        return ChatRequest(msgs, temperature = spec.temperature, maxTokens = if (voice) 600 else 2_000)
    }

    private suspend fun handleAction(input: String, intent: Intent, emit: suspend (OrchestratorEvent) -> Unit): String {
        val planner = actionPlanner; val executor = actionExecutor
        if (planner == null || executor == null) return "Je ne peux pas agir sur le téléphone dans cette configuration : aucune action n'a été exécutée."
        val provider = router.select(RoutingNeeds(ThinkingDepth.STANDARD, task = TaskCategory.ACTIONS, dataClass = DataClass.GENERAL))
        return when (val plan = planner.plan(input, provider)) {
            is ActionPlanner.Plan.Clarify -> plan.question
            is ActionPlanner.Plan.Unparseable -> "Je n'ai pas compris l'action demandée ; rien n'a été exécuté. Pouvez-vous la reformuler ?"
            is ActionPlanner.Plan.Call -> {
                emit(OrchestratorEvent.ActionProposed(plan.call))
                val outcome = executor.execute(plan.call, confirmed = false)
                emit(OrchestratorEvent.ActionResult(plan.call, outcome))
                ActionPlanner.report(plan.call, outcome)
            }
        }
    }

    /** Exécute une action après confirmation explicite de l'utilisateur (bouton « Confirmer »). */
    suspend fun executeConfirmed(call: ToolCall, ctx: ConversationContext): String {
        val executor = actionExecutor ?: return "Aucun moteur d'actions disponible."
        val text = ActionPlanner.report(call, executor.execute(call, confirmed = true))
        ctx.add(ChatMessage(Role.ASSISTANT, text))
        return text
    }

    private suspend fun handleScript(
        input: String, provider: ai.forge.models.LlmProvider, ctx: ConversationContext, voice: Boolean, memoryContext: String,
        researchNote: String?, bibliography: String?, securityRule: String?, emit: suspend (OrchestratorEvent) -> Unit,
    ): String? {
        val platform = ai.forge.editorial.Platform.detect(input)
        val seconds = Regex("(\\d{1,3})\\s*(s|sec|secondes)\\b").find(input)?.groupValues?.get(1)?.toInt()
            ?: Regex("(\\d{1,2})\\s*(min|minutes)\\b").find(input)?.groupValues?.get(1)?.toInt()?.times(60)
            ?: platform.defaultSeconds
        val market = marketTool?.let { runCatching { it.insights(input, platform) }.getOrNull() }
        // Demande d'analyse du marché (pas d'écriture de script) : rapport chiffré + recommandations.
        val folded = ai.forge.core.text.TextUtils.fold(input)
        if (Regex("(analys|marche|tendance|concurren|ce qui marche|niche)").containsMatchIn(folded) && !Regex("(ecri|redige|script)").containsMatchIn(folded)) {
            val data = market ?: "Aucune donnée de marché disponible (ajoutez une clé YouTube Data API dans Réglages, ou connectez-vous à Internet). Ne présente aucun chiffre comme réel."
            val r = provider.complete(request(Specialists[SpecialistId.EDITORIAL], ctx, voice, memoryContext,
                extra = "Tu es stratège YouTube pour Primestone Media. À partir de ces données réelles, donne : ce qui marche, ce qui ne marche pas, " +
                    "3 idées de vidéos précises avec titre et accroche, et ton verdict franc sur le potentiel du sujet. N'invente aucun chiffre.\n$data",
                securityRule = securityRule)).getOrElse { emit(OrchestratorEvent.Failed(it.message ?: "Échec")); return null }
            val text = (if (market != null && !voice) market + "\n\n" else "") + r.trim()
            emit(OrchestratorEvent.Token(text)); ctx.add(ChatMessage(Role.ASSISTANT, r.trim())); recordUsage(provider.id, ctx, r)
            return text
        }
        val studio = ai.forge.editorial.ScriptStudio
        val req = request(Specialists[SpecialistId.EDITORIAL], ctx, voice = false, memoryContext,
            extra = studio.prompt(input, platform, seconds, market, researchNote), securityRule = securityRule).copy(maxTokens = 2_500)
        val draft = provider.complete(req).getOrElse { emit(OrchestratorEvent.Failed(it.message ?: "Échec de génération")); return null }
        var script = studio.parse(draft, platform)
        var checks = studio.check(script, seconds)
        var raw = draft
        val critical = checks.filter { !it.ok && (it.label.startsWith("Script structuré") || it.label.startsWith("Pas d'introduction") || it.label.startsWith("Accroche")) }
        var revised = false
        if (critical.isNotEmpty()) {
            val fix = provider.complete(req.copy(messages = req.messages + listOf(
                ChatMessage(Role.ASSISTANT, draft),
                ChatMessage(Role.USER, "Corrige ces points en gardant exactement le même format :\n" + critical.joinToString("\n") { "- ${it.label} → ${it.fix}" }),
            ))).getOrNull()
            if (fix != null) {
                val s2 = studio.parse(fix, platform); val c2 = studio.check(s2, seconds)
                if (c2.count { it.ok } >= checks.count { it.ok }) { script = s2; checks = c2; raw = fix; revised = true }
            }
        }
        emit(OrchestratorEvent.ScriptReady(script, checks, revised))
        val text = buildString {
            append(raw.trim())
            if (!voice) { append("\n\n"); append(studio.report(checks)) }
            bibliography?.let { if (!voice) append("\n\nSources consultées :\n").append(it) }
        }
        emit(OrchestratorEvent.Token(text))
        ctx.add(ChatMessage(Role.ASSISTANT, raw.trim()))
        recordUsage(provider.id, ctx, raw)
        return text
    }

    private fun recordUsage(providerId: String, ctx: ConversationContext, output: String) {
        val input = ctx.turns.joinToString(" ") { it.content }
        router.usage.record(providerId, router.usage.estimateTokens(input), router.usage.estimateTokens(output))
    }

    internal fun taskOf(id: SpecialistId): TaskCategory = when (id) {
        SpecialistId.CONVERSATION, SpecialistId.MEMORY -> TaskCategory.CONVERSATION
        SpecialistId.REASONING, SpecialistId.QUALITY_CONTROL -> TaskCategory.REASONING
        SpecialistId.RESEARCH, SpecialistId.BROWSER -> TaskCategory.RESEARCH
        SpecialistId.DOCUMENT, SpecialistId.COMICS -> TaskCategory.DOCUMENTS
        SpecialistId.EDITORIAL -> TaskCategory.EDITORIAL
        SpecialistId.STUDY -> TaskCategory.STUDY
        SpecialistId.WRITING -> TaskCategory.WRITING
        SpecialistId.ANDROID_ACTION -> TaskCategory.ACTIONS
    }

    /**
     * Sensibilité des données : documents privés si la tâche porte sur la bibliothèque ou si un
     * souvenir documentaire est joint ; personnelles si des souvenirs personnels sont joints.
     */
    internal fun classify(intent: Intent, hits: List<MemoryHit>): DataClass = when {
        intent.primary in setOf(SpecialistId.DOCUMENT, SpecialistId.COMICS) -> DataClass.PRIVATE_DOCUMENTS
        hits.any { it.item.kind == MemoryKind.DOCUMENT || it.item.provenance.type == Provenance.Type.DOCUMENT } -> DataClass.PRIVATE_DOCUMENTS
        hits.isNotEmpty() -> DataClass.PERSONAL
        else -> DataClass.GENERAL
    }

    /** Les souvenirs issus de documents ou du Web sont encadrés comme données non fiables. */
    private suspend fun buildMemoryContext(
        hits: List<MemoryHit>,
        onSuspicious: suspend (String, List<String>) -> Unit,
    ): Pair<String, Boolean> {
        var external = false
        val text = hits.joinToString("\n") { h ->
            val type = h.item.provenance.type
            if (type == Provenance.Type.DOCUMENT || type == Provenance.Type.WEB) {
                external = true
                val w = UntrustedContent.wrap(h.item.provenance.reference, h.item.text)
                w.text
            } else "- [${h.item.kind.label}] ${h.item.text} (source : ${type.name.lowercase()})"
        }
        hits.filter { it.item.provenance.type == Provenance.Type.DOCUMENT || it.item.provenance.type == Provenance.Type.WEB }
            .forEach { h ->
                val f = UntrustedContent.scan(h.item.text)
                if (f.isNotEmpty()) onSuspicious(h.item.provenance.reference, f.map { it.pattern })
            }
        return text to external
    }

    private companion object {
        val GUARDED = setOf(SpecialistId.COMICS, SpecialistId.RESEARCH)
    }

    internal fun parseReview(text: String): Pair<String, String> {
        val verdict = Regex("VERDICT\\s*:\\s*(OK|REVISER)", RegexOption.IGNORE_CASE).find(text)
            ?.groupValues?.get(1)?.uppercase() ?: "OK"
        val issues = Regex("PROBL[EÈ]MES\\s*:\\s*(.*)", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(text)?.groupValues?.get(1)?.trim()?.ifEmpty { null } ?: "aucun"
        return verdict to issues
    }
}

package ai.forge.reasoning

import ai.forge.core.model.AutonomyLevel
import ai.forge.memory.*
import ai.forge.models.*
import ai.forge.security.*
import ai.forge.core.model.ChatMessage
import ai.forge.core.model.Role
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

class ActionFlowTest {
    private val specs = listOf(
        ToolSpec("android.alarm", "Programme un réveil (hour, minute, label).", AutonomyLevel.L2_REVERSIBLE, true),
        ToolSpec("android.share", "Partage un texte (text).", AutonomyLevel.L1_PROPOSE, true, externalEffect = true),
    )
    private val planner = ActionPlanner { specs }
    private fun memory() = MemoryManager(InMemoryMemoryStore(), HashingEmbedder())

    /** Exécuteur de test qui reproduit les décisions du vrai portail. */
    private class GateExecutor(specs: List<ToolSpec>) : ToolCallExecutor {
        val gate = PermissionGate(AutonomyLevel.L2_REVERSIBLE)
        val byName = specs.associateBy { it.name }
        val executed = mutableListOf<ToolCall>()
        override suspend fun execute(call: ToolCall, confirmed: Boolean): ActionOutcome {
            if (confirmed) gate.confirmOnce(call)
            return when (val d = gate.evaluate(byName.getValue(call.tool), call)) {
                PermissionGate.Decision.Allow -> { executed += call; ActionOutcome(ActionOutcome.Status.DONE, "réveil transmis à l'Horloge") }
                is PermissionGate.Decision.Ask -> ActionOutcome(ActionOutcome.Status.NEEDS_CONFIRMATION, d.reason)
                is PermissionGate.Decision.Deny -> ActionOutcome(ActionOutcome.Status.DENIED, d.reason)
            }
        }
    }

    @Test fun quickParseHandlesCommonFrenchAlarmPhrasings() {
        val c = planner.quickParse("Mets un réveil à 7h30 pour la vidéo")!!
        assertEquals(7, c.arguments["hour"]!!.jsonPrimitive.int); assertEquals(30, c.arguments["minute"]!!.jsonPrimitive.int)
        assertEquals("la video", c.arguments["label"]!!.jsonPrimitive.content)
        assertEquals(6, planner.quickParse("Réveille-moi à 6 h")!!.arguments["hour"]!!.jsonPrimitive.int)
        assertNull(planner.quickParse("Mets un réveil à 25h"))
        assertNull(planner.quickParse("Parle-moi de Superman"))
    }

    @Test fun parsesModelJsonAndRejectsUnknownTools() {
        val ok = planner.parse("Voici : {\"tool\": \"android.share\", \"arguments\": {\"text\": \"Salut\"}, \"rationale\": \"partage\"} fin")
        assertIs<ActionPlanner.Plan.Call>(ok)
        assertIs<ActionPlanner.Plan.Clarify>(planner.parse("{\"tool\": \"android.delete_all\"}"))
        assertIs<ActionPlanner.Plan.Clarify>(planner.parse("{\"tool\": null, \"question\": \"À quelle heure ?\"}"))
        assertIs<ActionPlanner.Plan.Unparseable>(planner.parse("je ne sais pas"))
    }

    @Test fun scenarioG_alarmIsExecutedThroughGateAndReportedFromRealOutcome() = runTest {
        val exec = GateExecutor(specs)
        val o = Orchestrator(ProviderRouter(listOf(ScriptedProvider { "ne doit pas servir" })), memory(), actionPlanner = planner, actionExecutor = exec)
        val ev = o.handle("Mets une alarme à 7h", ConversationContext("g"), voice = true).toList()
        val result = ev.filterIsInstance<OrchestratorEvent.ActionResult>().single()
        assertEquals(ActionOutcome.Status.DONE, result.outcome.status)
        assertEquals(1, exec.executed.size)
        assertTrue(ev.filterIsInstance<OrchestratorEvent.Completed>().single().fullText.startsWith("C'est transmis"))
    }

    @Test fun externalEffectNeedsConfirmationThenRunsOnce() = runTest {
        val exec = GateExecutor(specs)
        val provider = ScriptedProvider { "{\"tool\": \"android.share\", \"arguments\": {\"text\": \"Mon script\"}, \"rationale\": \"partage demandé\"}" }
        val o = Orchestrator(ProviderRouter(listOf(provider)), memory(), actionPlanner = planner, actionExecutor = exec)
        val ctx = ConversationContext("g2")
        val ev = o.handle("Partage mon script avec l'application de messages, envoie le message", ctx, voice = false).toList()
        val r = ev.filterIsInstance<OrchestratorEvent.ActionResult>().singleOrNull()
            ?: fail("Pas d'action : ${ev}")
        assertEquals(ActionOutcome.Status.NEEDS_CONFIRMATION, r.outcome.status)
        assertTrue(exec.executed.isEmpty())
        assertTrue(o.executeConfirmed(r.call, ctx).startsWith("C'est transmis"))
        assertEquals(1, exec.executed.size)
    }

    @Test fun withoutExecutorForgeSaysNothingWasDone() = runTest {
        val o = Orchestrator(ProviderRouter(listOf(ScriptedProvider { "" })), memory())
        val done = o.handle("Mets une alarme à 7h", ConversationContext("g3"), voice = false).toList()
            .filterIsInstance<OrchestratorEvent.Completed>().single()
        assertTrue(done.fullText.contains("aucune action n'a été exécutée"))
    }
}

class ResearchFlowTest {
    private fun memory() = MemoryManager(InMemoryMemoryStore(), HashingEmbedder())

    @Test fun researchSourcesAreInjectedAndBibliographyAppendedByCode() = runTest {
        val tool = ResearchTool { q -> ResearchContext("[1] Source A : le vibranium absorbe l'énergie.", "[1] Source A — https://a.example", 1, 2, 0) }
        val p = ScriptedProvider(capabilities = ProviderCapabilities(Locality.ON_DEVICE, 32_000, reasoningQuality = 5)) { req ->
            if (req.messages.joinToString("\n") { it.content }.contains("VERDICT")) "VERDICT: OK\nPROBLEMES: aucun" else "D'après [1], le vibranium absorbe l'énergie."
        }
        val o = Orchestrator(ProviderRouter(listOf(p)), memory(), researchTool = tool)
        val ev = o.handle("Fais une recherche sur les sources récentes concernant le vibranium", ConversationContext("r"), voice = false).toList()
        assertEquals(1, ev.filterIsInstance<OrchestratorEvent.ResearchDone>().single().sourceCount)
        assertTrue(p.requests.first().messages.joinToString("\n") { it.content }.contains("Source A"))
        val done = ev.filterIsInstance<OrchestratorEvent.Completed>().single().fullText
        assertTrue(done.endsWith("[1] Source A — https://a.example"), done)
    }

    @Test fun withoutResearchToolForgeSaysSo() = runTest {
        val p = ScriptedProvider(capabilities = ProviderCapabilities(Locality.ON_DEVICE, 32_000, reasoningQuality = 5)) { "VERDICT: OK\nPROBLEMES: aucun" }
        Orchestrator(ProviderRouter(listOf(p)), memory()).handle("Recherche les sources sur ce sujet", ConversationContext("r2"), voice = false).toList()
        assertTrue(p.requests.first().messages.joinToString("\n") { it.content }.contains("Aucune recherche Web n'a pu être effectuée"))
    }
}

class LibraryFlowTest {
    @Test fun comicsAnswerCitesRealLibraryReferences() = runTest {
        val lib = LibraryTool { listOf(LibraryPassage("Black Panther #8 (2016), p. 2", "Mon costume absorbe l'énergie cinétique", verified = false)) }
        val p = ScriptedProvider(capabilities = ProviderCapabilities(Locality.ON_DEVICE, 32_000, reasoningQuality = 5)) { req ->
            if (req.messages.joinToString("\n") { it.content }.contains("VERDICT")) "VERDICT: OK\nPROBLEMES: aucun" else "Piste : [B1]."
        }
        val o = Orchestrator(ProviderRouter(listOf(p)), MemoryManager(InMemoryMemoryStore(), HashingEmbedder()), libraryTool = lib)
        val ev = o.handle("Retrouve dans mes comics la preuve que le costume de Black Panther absorbe l'énergie", ConversationContext("l"), voice = false).toList()
        assertEquals(1, ev.filterIsInstance<OrchestratorEvent.LibraryResults>().single().passages.size)
        assertTrue(p.requests.any { r -> r.messages.joinToString("\n") { it.content }.contains("Black Panther #8 (2016), p. 2 (non vérifié)") })
        val done = ev.filterIsInstance<OrchestratorEvent.Completed>().single().fullText
        assertTrue(done.contains("[B1] Black Panther #8 (2016), p. 2 — piste OCR, page à consulter"), done)
    }
}

class OnDeviceSpeedTest {
    @Test fun expertQuestionOnDeviceIsSinglePassAndStreamed() = runTest {
        val p = ScriptedProvider(capabilities = ProviderCapabilities(Locality.ON_DEVICE, 32_000, reasoningQuality = 4)) { "Réponse directe." }
        val o = Orchestrator(ProviderRouter(listOf(p)), MemoryManager(InMemoryMemoryStore(), HashingEmbedder()))
        val ev = o.handle("Analyse pourquoi exactement la stratégie a échoué", ConversationContext("s"), voice = false).toList()
        assertEquals(1, p.requests.size)                     // une seule passe
        assertTrue(ev.none { it is OrchestratorEvent.QualityReview })
    }

    @Test fun comicsOnDeviceStreamInOnePassWithInstantCitationCheck() = runTest {
        val p = ScriptedProvider(capabilities = ProviderCapabilities(Locality.ON_DEVICE, 32_000, reasoningQuality = 4)) { "C'est dans Black Panther #8 [3]." }
        val o = Orchestrator(ProviderRouter(listOf(p)), MemoryManager(InMemoryMemoryStore(), HashingEmbedder()))
        val ev = o.handle("Dans quel comic Black Panther absorbe l'énergie ?", ConversationContext("s2"), voice = false).toList()
        assertEquals(1, p.requests.size)                     // plus de relecture qui bloque l'affichage
        assertTrue(ev.none { it is OrchestratorEvent.QualityReview })
        val done = ev.filterIsInstance<OrchestratorEvent.Completed>().single().fullText
        assertTrue(done.contains("[3] ne correspond à aucune source consultée"), done)
    }

    @Test fun citationCheckAcceptsRealSources() {
        val o = Orchestrator(ProviderRouter(emptyList()), MemoryManager(InMemoryMemoryStore(), HashingEmbedder()))
        assertEquals(null, o.citationWarning("Selon [1] et [2], puis [B1].", webSources = 2, libraryPassages = 1))
        assertTrue(o.citationWarning("Voir [4] et [B2].", 2, 1)!!.contains("[4], [B2]"))
    }
}

class ScriptFlowTest {
    private val goodScript = """
        ### TITRES
        1. La faille du costume de Black Panther
        2. Le vibranium a un point faible
        ### MINIATURE
        Masque fissuré
        ### ACCROCHES
        1. Le costume de T'Challa a une faille.
        2. Et si son armure était son piège ?
        ### SCRIPT
        [0:00] PLAN: masque | ÉCRAN: UNE FAILLE ? | TEXTE: Le costume de T'Challa a une faille.
        [0:03] PLAN: impact | ÉCRAN: énergie | TEXTE: Chaque coup est absorbé et stocké.
        [0:06] PLAN: masque | ÉCRAN: la faille | TEXTE: Et cette faille, c'est le costume lui-même.
        ### SOURCES
        - Black Panther #8
    """.trimIndent()

    @Test fun softIntroIsAutomaticallyRevisedAndReportAppended() = runTest {
        var n = 0
        val p = ScriptedProvider(capabilities = ProviderCapabilities(Locality.ON_DEVICE, 32_000, reasoningQuality = 4)) { _ ->
            n++
            if (n == 1) goodScript.replace("TEXTE: Le costume de T'Challa a une faille.", "TEXTE: Salut tout le monde, aujourd'hui on parle de Black Panther.")
            else goodScript
        }
        val o = Orchestrator(ProviderRouter(listOf(p)), MemoryManager(InMemoryMemoryStore(), HashingEmbedder()))
        val ev = o.handle("Écris-moi un script TikTok sur la faille du costume de Black Panther", ConversationContext("v"), voice = false).toList()
        val ready = ev.filterIsInstance<OrchestratorEvent.ScriptReady>().single()
        assertTrue(ready.revised)
        assertEquals("Le costume de T'Challa a une faille.", ready.script.beats.first().narration)
        assertTrue(p.requests.first().messages.joinToString("\n") { it.content }.contains("Règles de rétention"))
        val done = ev.filterIsInstance<OrchestratorEvent.Completed>().single().fullText
        assertTrue(done.contains("Contrôle de rétention"))
        assertEquals(2, p.requests.size)
    }

    @Test fun marketInsightsAreInjected() = runTest {
        val p = ScriptedProvider(capabilities = ProviderCapabilities(Locality.ON_DEVICE, 32_000, reasoningQuality = 4)) { goodScript }
        val market = MarketTool { _, _ -> "- Les titres en question surperforment (x3,2)" }
        Orchestrator(ProviderRouter(listOf(p)), MemoryManager(InMemoryMemoryStore(), HashingEmbedder()), marketTool = market)
            .handle("Fais un script Short YouTube sur Venom", ConversationContext("m"), voice = false).toList()
        assertTrue(p.requests.first().messages.joinToString("\n") { it.content }.contains("x3,2"))
    }
}

class PersonaTest {
    @Test fun personaGivesFrankVerdictsAndStaysOriginal() {
        val p = ai.forge.core.persona.ForgePersona.systemPrompt(voice = false)
        assertTrue(p.contains("Mauvaise idée")); assertTrue(p.contains("stratège")); assertTrue(p.contains("Jamais de flatterie"))
        assertFalse(p.contains("T'Challa")); assertFalse(p.contains("Black Panther"))   // inspiration, pas imitation
        assertTrue(ai.forge.core.persona.ForgePersona.systemPrompt(voice = true).length < 2_200)  // prompt compact = réponse plus rapide
    }
}

class DebateFlowTest {
    @Test fun debateAnswerIsAuditedAgainstRealEvidence() = runTest {
        val lib = LibraryTool { listOf(LibraryPassage("Black Panther #8, p. 14", "le costume absorbe l'impact", verified = true)) }
        val p = ScriptedProvider(capabilities = ProviderCapabilities(Locality.ON_DEVICE, 32_000, reasoningQuality = 4)) {
            "### POSITION\nAvantage T'Challa.\n### ARGUMENTS\n1. Le costume absorbe les coups [B1].\n2. Il est plus rapide que Thor.\n### VERDICT\nT'Challa."
        }
        val o = Orchestrator(ProviderRouter(listOf(p)), MemoryManager(InMemoryMemoryStore(), HashingEmbedder()), libraryTool = lib)
        val ev = o.handle("Débat : qui gagne entre Black Panther et Thor ?", ConversationContext("d"), voice = false).toList()
        val done = ev.filterIsInstance<OrchestratorEvent.Completed>().single().fullText
        assertTrue(done.contains("1/2"), done)
        assertTrue(done.contains("Non sourcé"))
        assertEquals(1, p.requests.size)                      // une seule passe, contrôle déterministe
        assertTrue(p.requests.first().messages.joinToString("\n") { it.content }.contains("B1 (vérifiée)"))
    }
}

class LessonsTest {
    @Test fun lessonsFromFeedbackAreInjectedForMatchingSpecialist() = runTest {
        val m = MemoryManager(InMemoryMemoryStore(), HashingEmbedder())
        m.remember(MemoryCandidate(MemoryKind.PROCEDURAL, "Leçon (Mauvaise source, comics) : Ne cite jamais Fandom comme preuve",
            Provenance(Provenance.Type.CORRECTION, "f1"), tags = setOf("leçon", "comics")), confirmed = true)
        m.remember(MemoryCandidate(MemoryKind.PROCEDURAL, "Leçon (Ton inadapté, conversation) : Sois plus bref le matin",
            Provenance(Provenance.Type.CORRECTION, "f2"), tags = setOf("leçon", "conversation", "général")), confirmed = true)
        val p = ScriptedProvider(capabilities = ProviderCapabilities(Locality.ON_DEVICE, 32_000, reasoningQuality = 4)) { "VERDICT: OK\nPROBLEMES: aucun" }
        Orchestrator(ProviderRouter(listOf(p)), m).handle("Dans quel comic Black Panther absorbe l'énergie ?", ConversationContext("l"), voice = false).toList()
        val sys = p.requests.first().messages.joinToString("\n") { it.content }
        assertTrue(sys.contains("Ne cite jamais Fandom comme preuve"))
        assertTrue(sys.contains("Sois plus bref le matin"))     // leçon générale : partout
    }
}

class ContextBudgetTest {
    @Test fun keepsMostRecentTurnsWithinBudgetAndAlwaysTheQuestion() {
        val turns = (1..50).map { ChatMessage(if (it % 2 == 0) Role.ASSISTANT else Role.USER, "message $it " + "x".repeat(300)) }
        val kept = ContextBudget.fit(turns, 1_000)
        assertTrue(kept.size in 2..12); assertEquals(turns.last(), kept.last())
        assertTrue(kept.sumOf { ContextBudget.estimate(it.content) } <= 1_000)
        val huge = listOf(ChatMessage(Role.USER, "y".repeat(100_000)))
        assertEquals(1, ContextBudget.fit(huge, 1_000).size)
        assertTrue(ContextBudget.fit(huge, 1_000).single().content.length < 10_000)
    }

    @Test fun longConversationNeverOverflowsSmallModel() = runTest {
        val p = ScriptedProvider(capabilities = ProviderCapabilities(Locality.ON_DEVICE, 4_096, reasoningQuality = 3)) { "Réponse " + "z".repeat(900) }
        val o = Orchestrator(ProviderRouter(listOf(p)), MemoryManager(InMemoryMemoryStore(), HashingEmbedder()))
        val ctx = ConversationContext("long", maxTurns = 500)
        repeat(30) { o.handle("Question numéro $it, parle-moi encore de Thor et de Mjolnir", ctx, voice = false).toList() }
        val last = p.requests.last()
        assertTrue(last.messages.sumOf { ContextBudget.estimate(it.content) } + last.maxTokens <= 4_096)
        assertTrue(last.messages.last().content.contains("Question numéro 29"))
    }

    @Test fun documentRequestAsksForStructuredMarkdown() = runTest {
        val p = ScriptedProvider(capabilities = ProviderCapabilities(Locality.ON_DEVICE, 8_192, reasoningQuality = 3)) { "# Doc" }
        Orchestrator(ProviderRouter(listOf(p)), MemoryManager(InMemoryMemoryStore(), HashingEmbedder()))
            .handle("Fais-moi un PDF sur l'histoire des Avengers", ConversationContext("pdf"), voice = false).toList()
        assertTrue(p.requests.first().messages.joinToString("\n") { it.content }.contains("document PDF"))
    }
}

class VisionTest {
    private fun mem() = MemoryManager(InMemoryMemoryStore(), HashingEmbedder())
    private val img = byteArrayOf(1, 2, 3)

    @Test fun imageGoesToVisionCapableEngine() = runTest {
        val blind = ScriptedProvider(id = "blind", capabilities = ProviderCapabilities(Locality.ON_DEVICE, 8_000, reasoningQuality = 5)) { "aveugle" }
        val seer = ScriptedProvider(id = "seer", capabilities = ProviderCapabilities(Locality.ON_DEVICE, 8_000, vision = true, reasoningQuality = 3)) { "Je vois T'Challa." }
        val o = Orchestrator(ProviderRouter(listOf(blind, seer)), mem())
        o.handle("Que se passe-t-il sur cette case ?", ConversationContext("v"), voice = false, images = listOf(img)).toList()
        assertEquals(1, seer.requests.single().images.size); assertTrue(blind.requests.isEmpty())
    }

    @Test fun withoutVisionForgeUsesOcrAndSaysSoNeverInvents() = runTest {
        val blind = ScriptedProvider(capabilities = ProviderCapabilities(Locality.ON_DEVICE, 8_000, reasoningQuality = 5)) { "ok" }
        val o = Orchestrator(ProviderRouter(listOf(blind)), mem())
        o.handle("Que dit cette page ?", ConversationContext("v2"), voice = false, images = listOf(img), imageText = "WAKANDA FOREVER").toList()
        val sent = blind.requests.single()
        assertTrue(sent.images.isEmpty())
        val all = sent.messages.joinToString("\n") { it.content }
        assertTrue(all.contains("ne voit pas les images")); assertTrue(all.contains("WAKANDA FOREVER"))
    }
}

class LatencyTest {
    @Test fun systemMessageIsIdenticalAcrossSpecialistsAndChannels() = runTest {
        val p = ScriptedProvider(capabilities = ProviderCapabilities(Locality.ON_DEVICE, 8_192, reasoningQuality = 4)) { "Ok." }
        val o = Orchestrator(ProviderRouter(listOf(p)), MemoryManager(InMemoryMemoryStore(), HashingEmbedder()))
        val ctx = ConversationContext("lat")
        o.handle("Salut, ça va ?", ctx, voice = true).toList()
        o.handle("Dans quel comic Black Panther absorbe l'énergie ?", ctx, voice = false).toList()
        o.handle("Écris-moi un plan pour ma semaine", ctx, voice = false).toList()
        val systems = p.requests.map { r -> r.messages.first { it.role == ai.forge.core.model.Role.SYSTEM }.content }.distinct()
        assertEquals(1, systems.size)                       // le moteur local peut réutiliser ce qu'il a lu
    }

    @Test fun contextWindowStartMovesInBlocks() {
        val turns = (1..40).map { ai.forge.core.model.ChatMessage(if (it % 2 == 1) ai.forge.core.model.Role.USER else ai.forge.core.model.Role.ASSISTANT, "x".repeat(320)) }
        val a = ContextBudget.fitStable(turns.take(30), budget = 2_000)
        val b = ContextBudget.fitStable(turns.take(32), budget = 2_000)
        assertTrue(a.size <= 30 && ContextBudget.fit(turns.take(30), 2_000).size >= a.size)
        // Début de fenêtre identique d'un tour à l'autre (ou avancé d'un bloc entier).
        val startA = 30 - a.size; val startB = 32 - b.size
        assertEquals(0, startA % 6); assertEquals(0, startB % 6)
        assertTrue(startA == startB || startB - startA == 6)
    }
}

class TurnMetricsTest {
    @Test fun timerMeasuresFirstTokenResearchAndSpeed() {
        var now = 0L
        val t = TurnTimer(voice = true) { now }
        now = 100; t.on(OrchestratorEvent.ResearchStarted("q"))
        now = 900; t.on(OrchestratorEvent.ResearchDone(2, "", 0))
        now = 1_500; t.on(OrchestratorEvent.Token("Bonjour"))
        now = 3_500; t.on(OrchestratorEvent.Token(" ".repeat(1) + "x".repeat(593)))
        val m = t.finish()
        assertEquals(800L, m.researchMs); assertEquals(1_500L, m.firstTokenMs); assertEquals(3_500L, m.totalMs)
        assertEquals(50.0, m.wordsPerSecond!!, 0.2)                  // 600 caractères ≈ 100 mots en 2 s
    }

    @Test fun orchestratorReportsMetricsForEachTurn() = runTest {
        val p = ScriptedProvider(capabilities = ProviderCapabilities(Locality.ON_DEVICE, 8_192, reasoningQuality = 4)) { "Réponse." }
        val o = Orchestrator(ProviderRouter(listOf(p)), MemoryManager(InMemoryMemoryStore(), HashingEmbedder()))
        val got = mutableListOf<TurnMetrics>()
        o.onTurnMetrics = { got += it }
        o.handle("Salut", ConversationContext("m"), voice = false).toList()
        assertEquals(1, got.size); assertEquals(false, got.single().failed); assertTrue(got.single().chars > 0)
    }
}

class ProjectMemoryTest {
    @Test fun projectNotesAreRememberedAndRecalledLater() = runTest {
        val p = ScriptedProvider(capabilities = ProviderCapabilities(Locality.ON_DEVICE, 8_192, reasoningQuality = 4)) { "Noté." }
        val mem = MemoryManager(InMemoryMemoryStore(), HashingEmbedder())
        val o = Orchestrator(ProviderRouter(listOf(p)), mem)
        o.handle("Mon projet s'appelle Wakanda Files : une série de 5 vidéos sur le vibranium", ConversationContext("p1"), voice = false).toList()
        assertTrue(mem.list().any { it.kind == ai.forge.memory.MemoryKind.PROJECT && it.project == "Wakanda Files" })
        // Nouvelle conversation : il suffit de citer le projet pour que Forge s'en souvienne.
        o.handle("Où en est Wakanda Files ?", ConversationContext("p2"), voice = false).toList()
        assertTrue(p.requests.last().messages.joinToString("\n") { it.content }.contains("une série de 5 vidéos sur le vibranium"))
    }

    @Test fun conversationTrimsInBlocks() {
        val c = ConversationContext("b", maxTurns = 12)
        repeat(13) { c.add(ai.forge.core.model.ChatMessage(ai.forge.core.model.Role.USER, "m$it")) }
        assertEquals("m6", c.turns.first().content); assertEquals(7, c.turns.size)
    }
}

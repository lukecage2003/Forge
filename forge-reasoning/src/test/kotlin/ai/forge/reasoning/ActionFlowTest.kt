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
            if (req.messages.first().content.contains("VERDICT")) "VERDICT: OK\nPROBLEMES: aucun" else "D'après [1], le vibranium absorbe l'énergie."
        }
        val o = Orchestrator(ProviderRouter(listOf(p)), memory(), researchTool = tool)
        val ev = o.handle("Fais une recherche sur les sources récentes concernant le vibranium", ConversationContext("r"), voice = false).toList()
        assertEquals(1, ev.filterIsInstance<OrchestratorEvent.ResearchDone>().single().sourceCount)
        assertTrue(p.requests.first().messages.first().content.contains("Source A"))
        val done = ev.filterIsInstance<OrchestratorEvent.Completed>().single().fullText
        assertTrue(done.endsWith("[1] Source A — https://a.example"), done)
    }

    @Test fun withoutResearchToolForgeSaysSo() = runTest {
        val p = ScriptedProvider(capabilities = ProviderCapabilities(Locality.ON_DEVICE, 32_000, reasoningQuality = 5)) { "VERDICT: OK\nPROBLEMES: aucun" }
        Orchestrator(ProviderRouter(listOf(p)), memory()).handle("Recherche les sources sur ce sujet", ConversationContext("r2"), voice = false).toList()
        assertTrue(p.requests.first().messages.first().content.contains("Aucune recherche Web n'a pu être effectuée"))
    }
}

class LibraryFlowTest {
    @Test fun comicsAnswerCitesRealLibraryReferences() = runTest {
        val lib = LibraryTool { listOf(LibraryPassage("Black Panther #8 (2016), p. 2", "Mon costume absorbe l'énergie cinétique", verified = false)) }
        val p = ScriptedProvider(capabilities = ProviderCapabilities(Locality.ON_DEVICE, 32_000, reasoningQuality = 5)) { req ->
            if (req.messages.first().content.contains("VERDICT")) "VERDICT: OK\nPROBLEMES: aucun" else "Piste : [B1]."
        }
        val o = Orchestrator(ProviderRouter(listOf(p)), MemoryManager(InMemoryMemoryStore(), HashingEmbedder()), libraryTool = lib)
        val ev = o.handle("Retrouve dans mes comics la preuve que le costume de Black Panther absorbe l'énergie", ConversationContext("l"), voice = false).toList()
        assertEquals(1, ev.filterIsInstance<OrchestratorEvent.LibraryResults>().single().passages.size)
        assertTrue(p.requests.any { r -> r.messages.first().content.contains("Black Panther #8 (2016), p. 2 (non vérifié)") })
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

    @Test fun comicsStillVerifiedEvenOnDevice() = runTest {
        val p = ScriptedProvider(capabilities = ProviderCapabilities(Locality.ON_DEVICE, 32_000, reasoningQuality = 4)) { "VERDICT: OK\nPROBLEMES: aucun" }
        val o = Orchestrator(ProviderRouter(listOf(p)), MemoryManager(InMemoryMemoryStore(), HashingEmbedder()))
        val ev = o.handle("Dans quel comic Black Panther absorbe l'énergie ?", ConversationContext("s2"), voice = false).toList()
        assertTrue(ev.any { it is OrchestratorEvent.QualityReview })
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
        assertTrue(p.requests.first().messages.first().content.contains("Règles de rétention"))
        val done = ev.filterIsInstance<OrchestratorEvent.Completed>().single().fullText
        assertTrue(done.contains("Contrôle de rétention"))
        assertEquals(2, p.requests.size)
    }

    @Test fun marketInsightsAreInjected() = runTest {
        val p = ScriptedProvider(capabilities = ProviderCapabilities(Locality.ON_DEVICE, 32_000, reasoningQuality = 4)) { goodScript }
        val market = MarketTool { _, _ -> "- Les titres en question surperforment (x3,2)" }
        Orchestrator(ProviderRouter(listOf(p)), MemoryManager(InMemoryMemoryStore(), HashingEmbedder()), marketTool = market)
            .handle("Fais un script Short YouTube sur Venom", ConversationContext("m"), voice = false).toList()
        assertTrue(p.requests.first().messages.first().content.contains("x3,2"))
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
        assertTrue(p.requests.first().messages.first().content.contains("B1 (vérifiée)"))
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
        val sys = p.requests.first().messages.first().content
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
        assertTrue(p.requests.first().messages.first().content.contains("document PDF"))
    }
}

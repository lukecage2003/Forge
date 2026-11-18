package ai.forge.reasoning

import ai.forge.core.model.AutonomyLevel
import ai.forge.memory.*
import ai.forge.models.*
import ai.forge.security.*
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

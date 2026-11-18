package ai.forge.evaluation

import ai.forge.comics.*
import ai.forge.memory.*
import ai.forge.models.ScriptedProvider
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class BenchmarkTest {
    @Test fun criticalFailureCannotBeHiddenByAverage() {
        val metrics = (1..20).map { Metric(BenchDomain.CONVERSATION, "m$it", 1.0, 0.5) } +
            Metric(BenchDomain.COMICS, "Absence d'invention", 0.9, 1.0, critical = true)
        val card = Scorecard(metrics)
        assertTrue(card.passRate > 0.9)
        assertEquals(Verdict.FAIL, card.verdict)
        assertTrue(card.report().contains("Erreurs critiques"))
        assertEquals(Verdict.PASS_WITH_WARNINGS, Scorecard(listOf(Metric(BenchDomain.MEMORY, "x", 0.1, 0.5))).verdict)
        assertEquals(Verdict.FAIL, Scorecard(emptyList()).verdict)
    }

    @Test fun memoryBenchmarkOnRealMemoryManager() = runTest {
        val metrics = MemoryBenchmark.run({ MemoryManager(InMemoryMemoryStore(), HashingEmbedder()) })
        val card = Scorecard(metrics)
        assertEquals(Verdict.PASS, card.verdict, card.report())
    }

    @Test fun referenceGuardFlagsInventedIssuesAndPages() {
        val ledger = ClaimLedger().apply {
            record(Claim("c", "x", "Black Panther", listOf(EvidenceSource(SourceTier.ORIGINAL_COMIC,
                ComicReference("Black Panther", issue = "8", page = 14), description = "d")), "d", EvidenceLevel.P1,
                status = VerificationStatus.PRIMARY_CHECKED))
        }
        assertTrue(ReferenceGuard.inventedReferences("On le voit dans Black Panther #8, page 14.", ledger).isEmpty())
        val inv = ReferenceGuard.inventedReferences("Voir Black Panther #9, page 22.", ledger)
        assertEquals(2, inv.size); assertTrue(inv[0].endsWith("Black Panther #9")); assertEquals("page 22", inv[1])
        assertEquals(Verdict.FAIL, Scorecard(listOf(ReferenceGuard.metric(listOf("ok", "Thor #3"), ledger))).verdict)
    }

    @Test fun latencyProbeMeasuresFirstToken() = runTest {
        var t = 0L
        val p = ScriptedProvider { "Je suis Forge, ton assistant." }
        val r = LatencyProbe.measure(p) { t += 100_000_000; t }
        assertNotNull(r.firstTokenMs); assertNull(r.failed)
        assertTrue(r.chars > 10)
        assertEquals(2, LatencyProbe.metrics(r).size)
    }
}

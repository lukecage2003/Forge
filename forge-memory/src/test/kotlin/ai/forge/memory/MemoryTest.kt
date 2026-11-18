package ai.forge.memory

import ai.forge.memory.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class MemoryTest {
    private var clock = 1_000L
    private fun manager() = MemoryManager(InMemoryMemoryStore(), HashingEmbedder(), now = { clock })
    private fun cand(text: String, key: String? = null, kind: MemoryKind = MemoryKind.PERSONAL, ttl: Long? = null) =
        MemoryCandidate(kind, text, Provenance(Provenance.Type.USER_EXPLICIT, "conv-1"), key = key, ttlMs = ttl, needsConfirmation = false)

    @Test fun storesAndRecallsWithProvenance() = runTest {
        val m = manager()
        m.remember(cand("Mon personnage préféré est Black Panther"))
        m.remember(cand("Je tourne mes vidéos Primestone le samedi matin"))
        val hits = m.recall(MemoryQuery("Quel est mon personnage préféré ?"))
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.first().item.text.contains("Black Panther"))
        assertEquals(Provenance.Type.USER_EXPLICIT, hits.first().item.provenance.type)
    }

    @Test fun duplicatesAreMergedNotDuplicated() = runTest {
        val m = manager()
        m.remember(cand("Ma vidéo sur Superman sort le 12 décembre"))
        val second = m.remember(cand("Ma vidéo sur Superman sort le 12 décembre."))
        assertIs<RememberOutcome.MergedIntoExisting>(second)
        assertEquals(1, m.recall(MemoryQuery("Superman")).size)
    }

    @Test fun contradictionOnSameKeyIsSurfacedAndCanSupersede() = runTest {
        val m = manager()
        m.remember(cand("Vitesse de voix : normale", key = "pref.voix.vitesse"))
        val r = m.remember(cand("Vitesse de voix : rapide", key = "pref.voix.vitesse"))
        assertIs<RememberOutcome.Conflict>(r)
        val newer = m.supersede(r.existing, r.candidate)
        val hits = m.recall(MemoryQuery("vitesse voix"))
        assertEquals(listOf(newer.id), hits.map { it.item.id })
    }

    @Test fun negatedStatementDetectedAsContradiction() = runTest {
        val m = manager()
        m.remember(cand("J'aime les films de Zack Snyder"))
        val r = m.remember(cand("Je n'aime pas les films de Zack Snyder"))
        assertIs<RememberOutcome.Conflict>(r)
    }

    @Test fun preferencesRequireConfirmation() = runTest {
        val m = manager()
        val c = MemoryCandidateExtractor().extract("Je préfère les réponses courtes le matin", "c1").single()
        assertIs<RememberOutcome.PendingConfirmation>(m.remember(c))
        assertIs<RememberOutcome.Stored>(m.remember(c, confirmed = true))
    }

    @Test fun ordinaryChatterIsNotMemorized() {
        val ex = MemoryCandidateExtractor()
        assertTrue(ex.extract("Je repensais au film Superman de Christopher Reeve", "c1").isEmpty())
        val explicit = ex.extract("Souviens-toi que mon examen de droit est le 3 juin", "c1").single()
        assertFalse(explicit.needsConfirmation)
        assertTrue(explicit.text.contains("examen de droit"))
    }

    @Test fun expiredMemoriesArePurgedAndHidden() = runTest {
        val m = manager()
        m.remember(cand("Code de la porte du studio : temporaire", ttl = 500))
        clock += 1_000
        assertTrue(m.recall(MemoryQuery("porte studio")).isEmpty())
        assertEquals(1, m.purgeExpired())
    }

    @Test fun exportContainsEverything() = runTest {
        val m = manager()
        m.remember(cand("Je travaille sur Forge"))
        assertTrue(m.exportJson().contains("Je travaille sur Forge"))
    }

    @Test fun bm25RanksRelevantDocumentFirst() {
        val idx = Bm25Index()
        idx.put("a", "Le vibranium absorbe l'énergie cinétique")
        idx.put("b", "Superman vole au-dessus de Metropolis")
        idx.put("c", "Recette de crêpes bretonnes")
        assertEquals("a", idx.search("absorption d'énergie par le vibranium").first().first)
    }
}

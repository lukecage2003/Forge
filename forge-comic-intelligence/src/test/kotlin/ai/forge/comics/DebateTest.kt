package ai.forge.comics

import kotlin.test.*

class DebateTest {
    private val ev = listOf(
        DebateEvidence("B1", "Black Panther #8 (2016), p. 14 : absorbe l'impact", SourceTier.ORIGINAL_COMIC, verified = true),
        DebateEvidence("S1", "Marvel Database : page Vibranium", SourceTier.WIKI_COMMUNITY, verified = false),
    )
    private val answer = """
        ### POSITION
        T'Challa encaisse mieux que Captain America.
        ### ARGUMENTS
        1. Son costume absorbe l'énergie cinétique [B1].
        2. Le vibranium neutralise les ondes sonores [S1].
        3. Il a battu Namor en un coup.
        ### FAIBLESSES DE MA POSITION
        Le costume a une limite de saturation.
        ### VERDICT
        Avantage T'Challa.
    """.trimIndent()

    @Test fun everyArgumentMustCiteVerifiedEvidence() {
        val a = DebateCoach.audit(answer, ev)
        assertEquals(3, a.arguments.size)
        assertEquals(1, a.solid)
        assertEquals("Il a battu Namor en un coup.", a.unsourced.single().text)
        assertEquals("wiki uniquement", a.weak.single().second)
        assertTrue(a.report().contains("1/3"))
    }

    @Test fun promptForbidsInventionWithoutEvidence() {
        assertTrue(DebateCoach.prompt(emptyList()).contains("n'avance aucun numéro"))
        assertTrue(DebateCoach.prompt(ev).contains("[B1] (vérifiée)") || DebateCoach.prompt(ev).contains("B1 (vérifiée)"))
        assertTrue(DebateCoach.isDebate("qui gagne entre hulk et thor"))
        assertFalse(DebateCoach.isDebate("raconte moi l'histoire de thor"))
    }
}

package ai.forge.core

import ai.forge.core.comics.*
import kotlin.test.*

class ComicsTest {
    private val primaryRef = ComicReference("Black Panther", issue = "8", edition = "Marvel 2016", year = 2016, page = 14, panel = 3)

    @Test fun wikiOnlyClaimCannotBeP1() {
        val ledger = ClaimLedger()
        val a = ledger.record(Claim("c1", "Le costume absorbe l'énergie cinétique", "Black Panther",
            listOf(EvidenceSource(SourceTier.WIKI_COMMUNITY, url = "https://marvel.fandom.com/x", description = "Page wiki")),
            "Mentionné par un wiki", EvidenceLevel.P1))
        assertTrue(a.downgraded)
        assertEquals(EvidenceLevel.P4, a.claim.level)
        assertTrue(ledger.notPublishableAsFact().any { it.id == "c1" })
    }

    @Test fun verifiedPrimaryWithPageIsP1() {
        val a = ClaimLedger().record(Claim("c2", "Le costume absorbe l'énergie cinétique", "Black Panther",
            listOf(EvidenceSource(SourceTier.ORIGINAL_COMIC, primaryRef, description = "Case montrant l'absorption")),
            "Dialogue explicite", EvidenceLevel.P1, status = VerificationStatus.PRIMARY_CHECKED))
        assertFalse(a.downgraded)
        assertEquals(EvidenceLevel.P1, a.claim.level)
    }

    @Test fun primaryWithoutPageIsAtMostP2() {
        val a = ClaimLedger().record(Claim("c3", "x", "Black Panther",
            listOf(EvidenceSource(SourceTier.ORIGINAL_COMIC, primaryRef.copy(page = null), description = "d")),
            "d", EvidenceLevel.P1, status = VerificationStatus.PRIMARY_CHECKED))
        assertEquals(EvidenceLevel.P2, a.claim.level)
    }

    @Test fun contradictionCapsAtP3() {
        val a = ClaimLedger().record(Claim("c4", "x", "Thor",
            listOf(EvidenceSource(SourceTier.ORIGINAL_COMIC, primaryRef, description = "d")),
            "d", EvidenceLevel.P1, contradictions = listOf("Contredit dans Thor #12"), status = VerificationStatus.PRIMARY_CHECKED))
        assertEquals(EvidenceLevel.P3, a.claim.level)
    }

    @Test fun noSourceIsHypothesis() {
        assertEquals(EvidenceLevel.P5, ClaimLedger().record(Claim("c5", "x", "y", emptyList(), "aucune", EvidenceLevel.P2)).claim.level)
    }

    @Test fun formattingSignalsUnknownFields() {
        val ledger = ClaimLedger()
        val c = ledger.record(Claim("c6", "Origine du vibranium", "Wakanda",
            listOf(EvidenceSource(SourceTier.ORIGINAL_COMIC, primaryRef.copy(panel = null), description = "d")),
            "Récit d'origine", EvidenceLevel.P2, status = VerificationStatus.PRIMARY_CHECKED)).claim
        val text = ledger.formatResult(c)
        assertTrue(text.contains("Black Panther #8"))
        assertTrue(text.contains("Case : non identifiée"))
    }

    @Test fun parsesComicFileNames() {
        val r = ComicFileName.parse("Black Panther 008 (2016) (Digital) (Zone-Empire).cbz")
        assertEquals("Black Panther", r.title)
        assertEquals("8", r.issue)
        assertEquals(2016, r.year)
        val r2 = ComicFileName.parse("Superman v2 #75 (1993).cbr")
        assertEquals("Superman", r2.title)
        assertEquals("75", r2.issue)
        val r3 = ComicFileName.parse("Watchmen - Intégrale.pdf")
        assertNull(r3.issue)
        assertNull(r3.year)
    }
}

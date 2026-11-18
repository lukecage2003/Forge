package ai.forge.editorial

import ai.forge.comics.*
import kotlinx.serialization.json.Json
import kotlin.test.*

class ProductionTest {
    private val ref = ComicReference("Black Panther", issue = "8", edition = "Marvel 2016", year = 2016, page = 14, sourceFileId = "drive:1")
    private fun claim(id: String, level: EvidenceLevel = EvidenceLevel.P1, continuity: String? = "Terre-616",
                      quote: String? = null, sources: List<EvidenceSource> = listOf(EvidenceSource(SourceTier.ORIGINAL_COMIC, ref, description = "case 3"))) =
        Claim(id, "Le costume absorbe l'énergie cinétique", "Black Panther", sources, "case 3", level,
            status = VerificationStatus.PRIMARY_CHECKED, verbatimQuote = quote, continuity = continuity)

    private fun script(vararg segs: ScriptSegment, format: FormatSpec = FormatSpec.short(60)) =
        ScriptDocument("Le costume de T'Challa", "Comprendre son costume", "fans", format, "technologie", segs.toList(), sources = listOf("Black Panther #8"))

    @Test fun oralStyleFlagsAcademicPhrasing() {
        val f = OralStyle.check("Il convient de noter que, nonobstant les apparences (voir plus haut), le héros gagne. Par conséquent, etc. on avance.")
        val kinds = f.map { it.excerpt }
        assertTrue("il convient de" in kinds); assertTrue("nonobstant" in kinds); assertTrue("par consequent" in kinds)
        assertTrue(f.any { it.kind == "parenthèse" }); assertTrue(f.any { it.kind == "abréviation" })
        assertTrue(OralStyle.check("Et là, tout bascule. T'Challa encaisse le coup. Et il le renvoie.").isEmpty())
    }

    @Test fun verifiedOnlyWithSolidSources() {
        val ledger = mapOf("c1" to claim("c1", quote = "Chaque coup me rend plus fort"))
        val ok = EditorialVerifier.verify(script(
            ScriptSegment("Hook", "Et si chaque coup le rendait plus fort ?"),
            ScriptSegment("Preuve", "En 2016, T'Challa le dit lui-même : « Chaque coup me rend plus fort ».", claimIds = listOf("c1")),
        ), ledger, currentYear = 2026)
        assertEquals(ScriptStatus.VERIFIED, ok.status, ok.summary())
    }

    @Test fun fictitiousOrInaccessibleSourcesAreRejected() {
        val fictitious = claim("f", sources = listOf(EvidenceSource(SourceTier.ORIGINAL_COMIC, null, description = "un comic quelque part")))
        val dead = claim("d", sources = listOf(EvidenceSource(SourceTier.ORIGINAL_COMIC, ref, description = "x", accessible = false)))
        for (c in listOf(fictitious, dead)) {
            val r = EditorialVerifier.verify(script(ScriptSegment("S", "Il absorbe l'énergie.", claimIds = listOf(c.id))), mapOf(c.id to c))
            assertEquals(ScriptStatus.REJECTED, r.status)
        }
        val missing = EditorialVerifier.verify(script(ScriptSegment("S", "x", claimIds = listOf("absent"))), emptyMap())
        assertEquals(ScriptStatus.REJECTED, missing.status)
    }

    @Test fun detectsDatesQuotesVersionsExtrapolationsAndShortcuts() {
        val ledger = mapOf(
            "a" to claim("a", quote = "Je suis le roi"),
            "b" to claim("b", continuity = "Ultimate"),
            "p3" to claim("p3", level = EvidenceLevel.P3),
            "x" to claim("x").copy(contradictions = listOf("Contredit dans #12")),
        )
        val r = EditorialVerifier.verify(script(
            ScriptSegment("Dates", "En 1998, il dit « Je suis le roi absolu de tout ».", claimIds = listOf("a")),
            ScriptSegment("Versions", "Son costume absorbe tout.", claimIds = listOf("a", "b")),
            ScriptSegment("Continuité", "Dans la version Ultimate, il absorbe tout.", claimIds = listOf("a"), continuity = "Ultimate"),
            ScriptSegment("Extrapolation", "Le vibranium vient d'une autre galaxie.", claimIds = listOf("p3")),
            ScriptSegment("Contradiction", "Le costume est indestructible.", claimIds = listOf("x")),
            ScriptSegment("Raccourci", "Black Panther est le plus puissant, tout le monde sait ça."),
            ScriptSegment("Sans preuve", "Sa première apparition date de 1966 dans le numéro 52."),
        ), ledger, currentYear = 2026)
        val kinds = r.issues.map { it.kind }.toSet()
        for (k in listOf(VerificationIssueKind.DATE, VerificationIssueKind.QUOTE, VerificationIssueKind.VERSION_CONFUSION,
            VerificationIssueKind.CONTINUITY, VerificationIssueKind.EXTRAPOLATION, VerificationIssueKind.SOURCE_CONTRADICTION,
            VerificationIssueKind.SHORTCUT, VerificationIssueKind.UNSUPPORTED)) assertTrue(k in kinds, "Manque $k\n${r.summary()}")
        assertEquals(ScriptStatus.NEEDS_REVIEW, r.status)
    }

    @Test fun hedgedExtrapolationIsAccepted() {
        val r = EditorialVerifier.verify(script(ScriptSegment("S", "Selon certains lecteurs, le vibranium viendrait d'ailleurs.", claimIds = listOf("p3"))),
            mapOf("p3" to claim("p3", level = EvidenceLevel.P3)))
        assertFalse(r.issues.any { it.kind == VerificationIssueKind.EXTRAPOLATION })
    }

    @Test fun verticalPlanTargetsThreeToFiveSecondsWithoutForcingImages() {
        val narration = (1..12).joinToString(" ") { "Phrase numéro $it avec quelques mots utiles." }
        val s = script(ScriptSegment("Idée", narration, visuals = listOf("Gros plan du costume", "Case de l'impact"), claimIds = listOf("c1")))
        val plan = ProductionPlanner.plan(s, mapOf("c1" to claim("c1")))
        val frames = plan.sequences.flatMap { it.frames }
        frames.dropLast(1).forEach { assertTrue(it.endMs - it.startMs in 2_500..5_500, "plan de ${it.endMs - it.startMs} ms") }
        assertEquals(3, frames.count { it.visual != null })   // 2 suggestions + 1 page vérifiée, rien d'inventé
        assertTrue(frames.any { it.visual?.contains("p. 14") == true })
        assertTrue(plan.cadenceGaps.isNotEmpty())
        assertTrue(plan.media.all { it.authorizationRequired })
        assertTrue(plan.subtitlesSrt.startsWith("1\n00:00:00,000 --> "))
    }

    @Test fun srtLinesRespectLength() {
        val frames = listOf(StoryboardFrame(1, 0, 6_000, "Ceci est une phrase assez longue pour devoir être découpée en plusieurs sous-titres lisibles sur un téléphone.", null))
        val srt = Subtitles.srt(frames)
        srt.lines().filter { it.isNotBlank() && !it.contains("-->") && it.toIntOrNull() == null }.forEach { assertTrue(it.length <= 42, it) }
        assertEquals("01:02:03,004", Subtitles.ts(3_723_004))
    }

    @Test fun motionStudioExportRequiresVerifiedScriptAudioAndAuthorizedImages() {
        val ledger = mapOf("c1" to claim("c1"))
        val s = script(ScriptSegment("Hook", "Et s'il encaissait tout ?"), ScriptSegment("Preuve", "Son costume absorbe les chocs.", claimIds = listOf("c1"), visuals = listOf("Case 3")))
        val report = EditorialVerifier.verify(s, ledger)
        val plan = ProductionPlanner.plan(s, ledger)
        val img = AuthorizedImage("i1", "Case 3", "/comics/bp8/p14.jpg", "bibliothèque personnelle, usage citation", true)
        val blocked = MotionStudioExporter.export(s, report, plan, listOf(img.copy(authorized = false, authorization = null)), null)
        assertIs<MotionStudioExporter.Result.Blocked>(blocked)
        assertEquals(2, blocked.reasons.size)
        val ready = MotionStudioExporter.export(s, report, plan, listOf(img), "/audio/narration.wav")
        assertIs<MotionStudioExporter.Result.Ready>(ready)
        val back = Json.decodeFromString<MotionStudioPackage>(ready.json)
        assertEquals("9:16", back.targetFormat); assertEquals(ScriptStatus.VERIFIED, back.scriptStatus)
    }
}

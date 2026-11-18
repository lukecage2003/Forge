package ai.forge.editorial

import kotlin.test.*

class ViralScriptTest {
    private val good = """
        ### TITRES
        1. Le costume de Black Panther est-il invincible ?
        2. La faille cachée du vibranium
        ### MINIATURE
        T'Challa fissuré, texte « INVINCIBLE ? »
        ### ACCROCHES
        1. Le costume de T'Challa a une faille.
        2. Et si le vibranium était son pire ennemi ?
        ### SCRIPT
        [0:00] PLAN: gros plan masque | ÉCRAN: UNE FAILLE ? | TEXTE: Le costume de T'Challa a une faille.
        [0:03] PLAN: zoom sur la poitrine | ÉCRAN: énergie stockée | TEXTE: Il absorbe chaque coup et stocke l'énergie.
        [0:06] PLAN: onde de choc | ÉCRAN: et après ? | TEXTE: Mais cette énergie doit bien ressortir.
        [0:09] PLAN: retour au masque | ÉCRAN: la faille | TEXTE: Et c'est exactement là que se cache la faille du costume.
        ### SOURCES
        - Black Panther #8 (2016)
    """.trimIndent()

    @Test fun detectsPlatforms() {
        assertEquals(Platform.TIKTOK, Platform.detect("un tiktok sur Venom"))
        assertEquals(Platform.YOUTUBE_SHORTS, Platform.detect("un Short YouTube"))
        assertEquals(Platform.YOUTUBE_LONG, Platform.detect("une vidéo YouTube de 12 minutes"))
    }

    @Test fun parsesStructuredOutput() {
        val s = ScriptStudio.parse(good, Platform.TIKTOK)
        assertEquals(2, s.titles.size); assertEquals(2, s.hooks.size); assertEquals(4, s.beats.size)
        assertEquals("gros plan masque", s.beats[0].visual); assertEquals("UNE FAILLE ?", s.beats[0].onScreenText)
        assertEquals("Le costume de T'Challa a une faille.", s.beats[0].narration)
        assertEquals(listOf("Black Panther #8 (2016)"), s.sources)
        val doc = ScriptStudio.toDocument(s, "faille du costume")
        assertEquals(4, doc.segments.size); assertTrue(doc.format.kind == FormatKind.SHORT_VERTICAL)
    }

    @Test fun goodShortPassesCoreChecks() {
        val s = ScriptStudio.parse(good, Platform.TIKTOK)
        val checks = ScriptStudio.check(s, targetSeconds = s.estimatedSeconds)
        listOf("Pas d'introduction molle", "Changement visuel", "Fin qui relance", "Faits sourcés").forEach { label ->
            assertTrue(checks.first { it.label.startsWith(label) }.ok, "$label\n${ScriptStudio.report(checks)}")
        }
    }

    @Test fun flagsSoftIntroLongShotsAndUnverifiedFacts() {
        val bad = good.replace("[0:00] PLAN: gros plan masque | ÉCRAN: UNE FAILLE ? | TEXTE: Le costume de T'Challa a une faille.",
            "[0:00] PLAN: face caméra | TEXTE: Salut tout le monde, aujourd'hui on va parler d'un sujet que j'adore vraiment beaucoup et qui est passionnant.")
            .replace("[0:09]", "[0:19]").replace("Il absorbe chaque coup", "Il absorbe [À VÉRIFIER] chaque coup")
        val checks = ScriptStudio.check(ScriptStudio.parse(bad, Platform.TIKTOK))
        assertFalse(checks.first { it.label == "Pas d'introduction molle" }.ok)
        assertFalse(checks.first { it.label.startsWith("Accroche") }.ok)
        assertFalse(checks.first { it.label.startsWith("Changement visuel") }.ok)
        assertFalse(checks.first { it.label.contains("à vérifier") }.ok)
        assertTrue(ScriptStudio.report(checks).contains("aucune ne garantit la viralité"))
    }

    @Test fun promptCarriesPlatformRulesInsightsAndOriginality() {
        val p = ScriptStudio.prompt("Venom", Platform.YOUTUBE_LONG, 600, marketInsights = "- Titres en question : +40 % de vues")
        assertTrue(p.contains("relance toutes les ~90s")); assertTrue(p.contains("+40 %")); assertTrue(p.contains("aucune image, musique ou extrait protégé"))
        assertTrue(ScriptStudio.prompt("Venom", Platform.TIKTOK).contains("Fin qui relance la boucle") || ScriptStudio.prompt("Venom", Platform.TIKTOK).contains("relance la boucle"))
    }

    @Test fun serializesAndExportsMarkdown() {
        val s = ScriptStudio.parse(good, Platform.TIKTOK)
        val json = kotlinx.serialization.json.Json.encodeToString(ViralScript.serializer(), s)
        assertEquals(s, kotlinx.serialization.json.Json.decodeFromString(ViralScript.serializer(), json))
        val md = ScriptStudio.toMarkdown(s)
        assertTrue(md.contains("| 0:00 | gros plan masque | UNE FAILLE ? |")); assertTrue(md.contains("## Accroches à tester"))
    }
}

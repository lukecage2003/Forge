package ai.forge.core

import ai.forge.core.brain.*
import ai.forge.core.agents.SpecialistId
import ai.forge.core.comics.*
import ai.forge.core.editorial.*
import ai.forge.core.export.Exporters
import ai.forge.core.research.*
import ai.forge.core.writing.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class EditorialWritingTest {
    private fun words(n: Int) = (1..n).joinToString(" ") { "mot$it" } + "."

    @Test fun formatTargetsAreConfigurable() {
        assertEquals(180, FormatSpec.short(60, 180).targetWords)
        assertEquals(3000, FormatSpec.youtubeLong(20, 150).targetWords)
        assertEquals(12600, FormatSpec.documentary(90, 140).targetWords)
    }

    @Test fun qcFlagsLengthHookAndUnsourcedIssueNumbers() {
        val script = ScriptDocument("Le vibranium", "Comprendre le vibranium", "fans", FormatSpec.short(60),
            "scientifique", listOf(
                ScriptSegment("Hook", "Dans Black Panther #8 page 14 on voit tout, et c'est une très longue phrase qui ne finit jamais vraiment."),
            ))
        val issues = ScriptQualityControl.check(script, emptyMap())
        assertTrue(issues.any { it.message.startsWith("Longueur") })
        assertTrue(issues.any { it.message.startsWith("Accroche trop lente") })
        assertTrue(issues.any { it.severity == QcIssue.Severity.BLOQUANT && it.message.contains("risque d'invention") })
    }

    @Test fun qcAcceptsWellFormedShortAndWarnsOnWeakClaims() {
        val weak = Claim("w1", "Le vibranium vient d'une météorite", "Wakanda",
            listOf(EvidenceSource(SourceTier.WIKI_COMMUNITY, description = "wiki")), "wiki", EvidenceLevel.P4)
        val script = ScriptDocument("Vibranium", "Une idée", "fans", FormatSpec.short(60, 180), "origine", listOf(
            ScriptSegment("Hook", "Et si le Wakanda venait du ciel ?"),
            ScriptSegment("Idée", words(170), claimIds = listOf("w1")),
        ))
        val issues = ScriptQualityControl.check(script, mapOf("w1" to weak))
        assertFalse(issues.any { it.message.startsWith("Longueur") })
        assertTrue(issues.any { it.message.contains("P4") })
        assertTrue(Exporters.scriptToMarkdown(script).contains("## 2. Idée"))
    }

    @Test fun shortBriefRequiresRebuildNotCut() {
        val long = ScriptDocument("Long", "Tout sur Wakanda", "fans", FormatSpec.youtubeLong(), "histoire", emptyList())
        val prompt = ScriptPromptBuilder.build(EditorialRole.SCRIPT_DOCTOR, ScriptBrief("Wakanda", FormatSpec.short(), sourceScript = long))
        assertTrue(prompt.contains("ne coupe pas le script long"))
        assertTrue(prompt.contains("[À VÉRIFIER]"))
    }

    @Test fun writingAnalyzerFindsFrenchTypographyIssues() {
        val issues = WritingAnalyzer.typographyIssues("Bonjour! Comment vas-tu? Rendez-vous à 10:30 sur https://exemple.fr \"ok\"")
        assertTrue(issues.any { it.contains("« ! »") })
        assertTrue(issues.any { it.contains("« ? »") })
        assertFalse(issues.any { it.contains("10:30") || it.contains("https") })
        assertTrue(issues.any { it.startsWith("Guillemets") })
    }

    @Test fun correctionPromptHonorsLevelAndSeparatesObjectiveFromStyle() {
        val p = WritingPrompts.correction(CorrectionRequest("Je suis aller au cinema hier", CorrectionLevel.MINIMAL))
        assertTrue(p.contains("Conserve chaque mot"))
        assertTrue(p.contains("« objective »"))
        val key = WritingPrompts.answerKey("Q1. Définir le contrat.", AnswerKeyMode.FAITHFUL, null, withGrading = true)
        assertTrue(key.contains("non officiel"))
        assertTrue(key.contains("Aucun cours n'est fourni"))
    }

    @Test fun sourceClassificationAndCorroboration() {
        val c = SourceClassifier()
        assertEquals(SourceType.COMMUNITY, c.classify("https://marvel.fandom.com/wiki/Vibranium"))
        assertEquals(SourceType.PRIMARY, c.classify("https://www.marvel.com/comics/issue/1"))
        assertEquals(SourceType.INSTITUTIONAL, c.classify("https://www.insee.fr/fr/statistiques"))
        assertEquals(SourceType.UNVERIFIED, c.classify("pas une url"))
        c.learn("monblog.fr", SourceType.SPECIALIZED)
        assertEquals(SourceType.SPECIALIZED, c.classify("https://monblog.fr/article"))

        val cites = listOf(
            Citation("https://lemonde.fr/a", "a", SourceType.JOURNALISTIC, accessedAtIso = "2026-11-18"),
            Citation("https://www.lemonde.fr/b", "b", SourceType.JOURNALISTIC, accessedAtIso = "2026-11-18"),
        )
        val a = Corroboration.assess(cites, requiresPrimary = false, recentTopic = true)
        assertEquals(1, a.independentSources)
        assertFalse(a.sufficient)
        assertTrue(a.note.contains("revérifier"))
    }

    @Test fun brainTurnsCommentedFeedbackIntoLessonAndEvaluates() = runTest {
        val log = FeedbackLog()
        val f = Feedback("f1", "c1", SpecialistId.COMICS, "v1", FeedbackKind.WRONG_SOURCE, "q", "r",
            userComment = "Ne cite jamais Fandom comme preuve", atMs = 0)
        log.add(f)
        log.add(f.copy(id = "f2", kind = FeedbackKind.CORRECT, userComment = null))
        assertEquals(0.5, log.satisfaction()[SpecialistId.COMICS to "v1"])
        val lesson = log.lessonFrom(f)!!
        assertTrue(lesson.needsConfirmation)
        assertTrue(lesson.text.contains("Fandom"))

        val results = Evaluator.run(Evaluator.honestyBaseline) { input ->
            if (input.contains("numéro exact")) "Je ne peux pas l'affirmer sans source vérifiée." else "D'accord."
        }
        assertTrue(results.all { it.passed }, results.filterNot { it.passed }.toString())

        val reg = PromptRegistry()
        reg.publish(SpecialistId.COMICS, PromptRegistry.Version("v1", "a", "init", 0))
        reg.publish(SpecialistId.COMICS, PromptRegistry.Version("v2", "b", "leçon fandom", 1))
        assertEquals("v2", reg.active(SpecialistId.COMICS)!!.id)
        assertEquals("v1", reg.rollback(SpecialistId.COMICS)!!.id)
    }
}

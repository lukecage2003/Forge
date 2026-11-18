package ai.forge.writing

import kotlin.test.*
import kotlinx.coroutines.test.runTest

class WritingTest {    @Test fun writingAnalyzerFindsFrenchTypographyIssues() {
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

}

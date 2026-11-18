package ai.forge.evaluation

import kotlin.test.*
import kotlinx.coroutines.test.runTest

class BrainTest {    @Test fun brainTurnsCommentedFeedbackIntoLessonAndEvaluates() = runTest {
        val log = FeedbackLog()
        val f = Feedback("f1", "c1", "comics", "v1", FeedbackKind.WRONG_SOURCE, "q", "r",
            userComment = "Ne cite jamais Fandom comme preuve", atMs = 0)
        log.add(f)
        log.add(f.copy(id = "f2", kind = FeedbackKind.CORRECT, userComment = null))
        assertEquals(0.5, log.satisfaction()["comics" to "v1"])
        val lesson = log.lessonFrom(f)!!
        assertTrue(lesson.needsConfirmation)
        assertTrue(lesson.text.contains("Fandom"))

        val results = Evaluator.run(Evaluator.honestyBaseline) { input ->
            if (input.contains("numéro exact")) "Je ne peux pas l'affirmer sans source vérifiée." else "D'accord."
        }
        assertTrue(results.all { it.passed }, results.filterNot { it.passed }.toString())

        val reg = PromptRegistry()
        reg.publish("comics", PromptRegistry.Version("v1", "a", "init", 0))
        reg.publish("comics", PromptRegistry.Version("v2", "b", "leçon fandom", 1))
        assertEquals("v2", reg.active("comics")!!.id)
        assertEquals("v1", reg.rollback("comics")!!.id)
    }

}

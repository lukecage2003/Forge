package ai.forge.academy

import java.time.LocalDate
import kotlin.test.*

class AcademyTest {
    private val pages = listOf(
        "Le contrat désigne un accord de volontés destiné à créer des obligations.\n\nLa formation du contrat suppose un consentement libre et éclairé.",
        "On appelle vice du consentement l'erreur, le dol ou la violence qui altèrent la volonté.\n\nLa nullité relative protège un intérêt privé.",
    )
    private val course = Course("droit", "Droit des contrats", "Droit civil", CourseLibrary.chunk("droit", pages, targetWords = 20))

    @Test fun courseSearchReturnsLocatedPassages() {
        val lib = CourseLibrary().apply { add(course) }
        val hit = lib.search("vices du consentement dol").first()
        assertEquals(2, hit.page)
        assertEquals("Droit des contrats, p. 2", hit.citation(course.title))
        lib.remove("droit")
        assertTrue(lib.search("contrat").isEmpty())
    }

    @Test fun flashcardsComeVerbatimFromDefinitions() {
        val cards = course.chunks.flatMap { FlashcardExtractor.extract(it, course.title) }
        val notions = cards.map { it.notion }
        assertTrue("contrat" in notions, notions.toString())
        assertTrue("vice du consentement" in notions, notions.toString())
        assertTrue(cards.all { it.courseRef != null })
        assertTrue(cards.first { it.notion == "contrat" }.back.startsWith("Le contrat désigne"))
    }

    @Test fun sm2SchedulesAndResets() {
        val d = LocalDate.of(2026, 11, 18)
        var c = Flashcard("1", "n", "q", "r", null)
        c = SpacedRepetition.review(c, 5, d); assertEquals(1, c.intervalDays)
        c = SpacedRepetition.review(c, 5, d); assertEquals(6, c.intervalDays)
        c = SpacedRepetition.review(c, 4, d); assertTrue(c.intervalDays >= 15)
        assertEquals(d.plusDays(c.intervalDays.toLong()).toString(), c.dueIso)
        c = SpacedRepetition.review(c, 1, d); assertEquals(0, c.repetitions); assertEquals(1, c.intervalDays)
        assertTrue(c.ease >= 1.3)
        assertEquals(1, SpacedRepetition.due(listOf(c), d.plusDays(1)).size)
    }

    @Test fun groundingDistinguishesCourseFromExternal() {
        val ok = AcademyPrompts.checkGrounding(
            "[Cours : Droit des contrats, p. 1] Le contrat est un accord.\n\n[Hors cours] Dans la vie courante, signer un bail en est un exemple.",
            setOf("Droit des contrats, p. 1"))
        assertTrue(ok.ok); assertEquals(1, ok.courseParagraphs); assertEquals(1, ok.externalParagraphs)
        val bad = AcademyPrompts.checkGrounding("Le contrat est un accord.\n\n[Cours : Droit, p. 99] invention", setOf("Droit des contrats, p. 1"))
        assertFalse(bad.ok); assertEquals(1, bad.untagged.size); assertEquals(listOf("Droit, p. 99"), bad.unknownReferences)
        val prompt = AcademyPrompts.explain("contrat", emptyList(), Level.BEGINNER, ExplanationStyle.ANALOGY, voice = true)
        assertTrue(prompt.contains("Aucun passage de cours")); assertTrue(prompt.contains("selon ton cours"))
    }

    @Test fun quizGapsPlanningAndExam() {
        val q = QuizQuestion("q1", "nullité", "Quelle nullité protège un intérêt privé ?", expectedPoints = listOf("nullité relative", "intérêt privé"))
        val (score, missing) = Quiz.gradeOpen(q, "C'est la nullité relative.")
        assertEquals(0.5, score); assertEquals(listOf("intérêt privé"), missing)

        val attempts = listOf(
            Attempt("a", "contrat", true, "2026-11-01"), Attempt("b", "contrat", true, "2026-11-02"),
            Attempt("c", "nullité", false, "2026-11-01"), Attempt("d", "nullité", true, "2026-11-02"), Attempt("e", "nullité", false, "2026-11-03"),
        )
        val gaps = GapDetector.detect(attempts, listOf("contrat", "nullité", "dol"))
        assertEquals(listOf("dol", "nullité"), gaps.map { it.notion })

        val today = LocalDate.of(2026, 11, 18)
        val plan = StudyPlanner.plan(listOf("contrat", "nullité", "dol"), gaps, today, today.plusDays(7), perDay = 2)
        assertEquals("simulation d'examen", plan.last().kind)
        val counts = plan.dropLast(1).flatMap { it.notions }.groupingBy { it }.eachCount()
        assertTrue(counts.getValue("dol") > counts.getValue("contrat"))

        val pool = listOf("contrat", "nullité", "dol").flatMap { n -> (1..3).map { QuizQuestion("$n$it", n, "?") } }
        val exam = ExamSimulator.build(pool, gaps, count = 4)
        assertEquals(4, exam.questions.size)
        assertEquals(setOf("contrat", "nullité", "dol"), exam.questions.map { it.notion }.toSet())
        assertTrue(exam.disclaimer.contains("peuvent différer"))
    }
}

package ai.forge.academy

import ai.forge.core.text.TextUtils
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.roundToInt

@Serializable
data class Flashcard(
    val id: String,
    val notion: String,
    val front: String,
    val back: String,
    /** Référence au cours, ou null si la carte vient d'une explication externe. */
    val courseRef: String?,
    val repetitions: Int = 0,
    val intervalDays: Int = 0,
    val ease: Double = 2.5,
    val dueIso: String? = null,
)

/**
 * Révision espacée — algorithme SM-2 (SuperMemo 2), éprouvé et simple.
 * Note de 0 (oubli total) à 5 (réponse parfaite) ; en dessous de 3, la carte repart à zéro.
 */
object SpacedRepetition {
    fun review(card: Flashcard, grade: Int, today: LocalDate): Flashcard {
        require(grade in 0..5) { "Note entre 0 et 5" }
        val ease = max(1.3, card.ease + (0.1 - (5 - grade) * (0.08 + (5 - grade) * 0.02)))
        val (reps, interval) = if (grade < 3) 0 to 1 else when (card.repetitions) {
            0 -> 1 to 1
            1 -> 2 to 6
            else -> card.repetitions + 1 to (card.intervalDays * ease).roundToInt()
        }
        return card.copy(repetitions = reps, intervalDays = interval, ease = ease, dueIso = today.plusDays(interval.toLong()).toString())
    }

    fun due(cards: List<Flashcard>, today: LocalDate) = cards.filter { it.dueIso == null || !LocalDate.parse(it.dueIso).isAfter(today) }
}

/**
 * Extraction déterministe de cartes à partir des définitions explicites du cours
 * (« X est … », « X désigne … », « On appelle X … »). Le modèle de langage peut en proposer
 * d'autres, mais celles-ci sont fidèles mot pour mot au cours.
 */
object FlashcardExtractor {
    private val patterns = listOf(
        // « On appelle X Y » : X s'arrête devant l'article qui ouvre la définition Y.
        Regex("^(?:On appelle|On nomme)\\s+(.{3,60}?)\\s+((?:l'|le |la |les |un |une |des |ce |cette |tout |toute ).{8,})$", RegexOption.IGNORE_CASE),
        Regex("^(?:L[ea']\\s?|Les\\s+|Un\\s+|Une\\s+)?(.{3,60}?)\\s+(?:désigne|se définit comme|correspond à|est défini(?:e)? comme)\\s+(.{10,})$", RegexOption.IGNORE_CASE),
    )

    fun extract(chunk: CourseChunk, courseTitle: String): List<Flashcard> =
        TextUtils.sentences(chunk.text).mapNotNull { s ->
            patterns.firstNotNullOfOrNull { it.find(s.trim().trimEnd('.')) }?.let { m ->
                val notion = m.groupValues[1].trim()
                Flashcard("${chunk.id}-${notion.hashCode()}", notion, "Que désigne « $notion » ?", s.trim(), chunk.citation(courseTitle))
            }
        }
}

@Serializable
data class QuizQuestion(
    val id: String,
    val notion: String,
    val prompt: String,
    val choices: List<String> = emptyList(),
    val correctIndex: Int? = null,
    /** Pour les questions ouvertes : éléments attendus (évaluation indicative). */
    val expectedPoints: List<String> = emptyList(),
    val courseRef: String? = null,
)

@Serializable
data class Attempt(val questionId: String, val notion: String, val correct: Boolean, val dateIso: String)

object Quiz {
    fun gradeChoice(q: QuizQuestion, chosen: Int) = q.correctIndex == chosen

    /**
     * Évaluation INDICATIVE d'une réponse ouverte : proportion des points attendus présents.
     * Ce n'est pas une note officielle ; le détail des points manquants est plus utile que le score.
     */
    fun gradeOpen(q: QuizQuestion, answer: String): Pair<Double, List<String>> {
        val ans = TextUtils.stems(answer).toSet()
        val missing = q.expectedPoints.filter { p -> TextUtils.stems(p).let { s -> s.isNotEmpty() && s.count { it in ans }.toDouble() / s.size < 0.6 } }
        val score = if (q.expectedPoints.isEmpty()) 0.0 else 1.0 - missing.size.toDouble() / q.expectedPoints.size
        return score to missing
    }
}

data class Gap(val notion: String, val accuracy: Double, val attempts: Int, val reason: String)

/** Détection des lacunes : faible réussite récente, ou notion jamais travaillée. */
object GapDetector {
    fun detect(attempts: List<Attempt>, notions: Collection<String>, threshold: Double = 0.7, recentPerNotion: Int = 5): List<Gap> =
        notions.map { n ->
            val recent = attempts.filter { it.notion == n }.sortedBy { it.dateIso }.takeLast(recentPerNotion)
            when {
                recent.isEmpty() -> Gap(n, 0.0, 0, "jamais travaillée")
                else -> {
                    val acc = recent.count { it.correct }.toDouble() / recent.size
                    Gap(n, acc, recent.size, if (acc < threshold) "réussite ${(acc * 100).roundToInt()} % sur les ${recent.size} dernières tentatives" else "")
                }
            }
        }.filter { it.reason.isNotEmpty() }.sortedBy { it.accuracy }
}

data class StudySession(val date: LocalDate, val notions: List<String>, val kind: String)

/**
 * Calendrier de révision jusqu'à l'examen : les lacunes reviennent plus souvent ;
 * dernière journée réservée à une simulation d'examen.
 */
object StudyPlanner {
    fun plan(notions: List<String>, gaps: List<Gap>, today: LocalDate, examDate: LocalDate, perDay: Int = 3): List<StudySession> {
        val days = ChronoUnit.DAYS.between(today, examDate).toInt()
        require(days >= 1) { "La date d'examen doit être postérieure à aujourd'hui" }
        val weak = gaps.map { it.notion }.toSet()
        // Les notions faibles comptent double dans la rotation.
        val rotation = notions.flatMap { if (it in weak) listOf(it, it) else listOf(it) }.ifEmpty { return emptyList() }
        var cursor = 0
        val sessions = (0 until days - 1).map { d ->
            val picked = linkedSetOf<String>()
            var guard = 0
            while (picked.size < minOf(perDay, notions.size) && guard++ < rotation.size * 2) picked += rotation[cursor++ % rotation.size]
            StudySession(today.plusDays(d.toLong()), picked.toList(), if (d % 3 == 2) "quiz" else "révision")
        }
        return sessions + StudySession(examDate.minusDays(1), notions, "simulation d'examen")
    }
}

data class ExamSimulation(val questions: List<QuizQuestion>, val durationMinutes: Int, val disclaimer: String)

object ExamSimulator {
    /** Sélectionne les questions en couvrant toutes les notions, avec priorité aux lacunes. */
    fun build(pool: List<QuizQuestion>, gaps: List<Gap>, count: Int, minutesPerQuestion: Int = 3): ExamSimulation {
        val weak = gaps.map { it.notion }.toSet()
        val byNotion = pool.groupBy { it.notion }.toList().sortedByDescending { it.first in weak }
        val chosen = mutableListOf<QuizQuestion>()
        var round = 0
        while (chosen.size < count && byNotion.any { it.second.size > round }) {
            byNotion.forEach { (_, qs) -> if (chosen.size < count && qs.size > round) chosen += qs[round] }
            round++
        }
        return ExamSimulation(chosen, chosen.size * minutesPerQuestion,
            "Simulation d'entraînement : le format et le barème réels de l'examen peuvent différer.")
    }
}

package ai.forge.evaluation

/** Domaines de bancs d'essai (§16). */
enum class BenchDomain { CONVERSATION, MEMORY, RESEARCH, COMICS, EDITORIAL, ACADEMY, ACTIONS, SECURITY, PERFORMANCE }

data class Metric(
    val domain: BenchDomain,
    val name: String,
    val value: Double,
    val threshold: Double,
    val higherIsBetter: Boolean = true,
    /** Un échec critique fait échouer le bilan, quelle que soit la moyenne. */
    val critical: Boolean = false,
    val unit: String = "",
) {
    val passed: Boolean get() = if (higherIsBetter) value >= threshold else value <= threshold
}

enum class Verdict { PASS, PASS_WITH_WARNINGS, FAIL }

/**
 * Bilan d'évaluation. Règle du §16 : « aucun score global ne doit masquer une erreur critique ».
 * Le verdict est FAIL dès qu'une métrique critique échoue ; le taux de réussite n'est qu'indicatif.
 */
class Scorecard(val metrics: List<Metric>) {
    val criticalFailures get() = metrics.filter { it.critical && !it.passed }
    val warnings get() = metrics.filter { !it.critical && !it.passed }
    val passRate: Double get() = if (metrics.isEmpty()) 0.0 else metrics.count { it.passed }.toDouble() / metrics.size

    val verdict: Verdict get() = when {
        metrics.isEmpty() -> Verdict.FAIL
        criticalFailures.isNotEmpty() -> Verdict.FAIL
        warnings.isNotEmpty() -> Verdict.PASS_WITH_WARNINGS
        else -> Verdict.PASS
    }

    fun report(): String = buildString {
        appendLine("# Bilan d'évaluation — ${verdict.name}")
        if (criticalFailures.isNotEmpty()) {
            appendLine("## Erreurs critiques (bloquantes)")
            criticalFailures.forEach { appendLine("- [${it.domain}] ${it.name} : ${fmt(it)}") }
        }
        metrics.groupBy { it.domain }.forEach { (d, ms) ->
            appendLine("## $d")
            ms.forEach { appendLine("- ${if (it.passed) "✓" else if (it.critical) "✗ CRITIQUE" else "✗"} ${it.name} : ${fmt(it)}") }
        }
        appendLine("Taux de réussite indicatif : ${"%.0f".format(passRate * 100)} % (ne compense jamais une erreur critique).")
    }.trimEnd()

    private fun fmt(m: Metric) = "%.3f%s (seuil %s %.3f%s)".format(m.value, m.unit, if (m.higherIsBetter) "≥" else "≤", m.threshold, m.unit)
}

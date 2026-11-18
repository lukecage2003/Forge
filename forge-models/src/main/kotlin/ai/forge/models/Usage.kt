package ai.forge.models

import java.time.Instant
import java.time.ZoneId

/** Tarif saisi par l'utilisateur (les prix changent : Forge ne les invente pas). */
data class Pricing(val currency: String, val inputPerMillionTokens: Double, val outputPerMillionTokens: Double)

/** Limites d'usage d'un fournisseur (§14). Null = pas de limite. */
data class UsageLimits(val maxRequestsPerDay: Int? = null, val maxCostPerMonth: Double? = null)

data class UsageRecord(val providerId: String, val atMs: Long, val inputTokens: Int, val outputTokens: Int, val cost: Double?)

/**
 * Compteur d'usage et de coût estimé. Les jetons sont estimés (≈ 4 caractères par jeton) quand
 * le fournisseur ne les renvoie pas : le coût affiché est donc une ESTIMATION, présentée comme telle.
 */
class UsageMeter(
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val records = mutableListOf<UsageRecord>()
    private val pricing = mutableMapOf<String, Pricing>()
    private val limits = mutableMapOf<String, UsageLimits>()

    fun setPricing(providerId: String, p: Pricing?) { if (p == null) pricing.remove(providerId) else pricing[providerId] = p }
    fun setLimits(providerId: String, l: UsageLimits) { limits[providerId] = l }

    fun estimateTokens(text: String): Int = (text.length / 4).coerceAtLeast(1)

    fun record(providerId: String, inputTokens: Int, outputTokens: Int): UsageRecord {
        val cost = pricing[providerId]?.let { inputTokens / 1e6 * it.inputPerMillionTokens + outputTokens / 1e6 * it.outputPerMillionTokens }
        return UsageRecord(providerId, now(), inputTokens, outputTokens, cost).also { records += it }
    }

    private fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    fun requestsToday(providerId: String): Int = records.count { it.providerId == providerId && day(it.atMs) == day(now()) }

    fun costThisMonth(providerId: String): Double {
        val d = day(now())
        return records.filter { it.providerId == providerId && day(it.atMs).let { r -> r.year == d.year && r.month == d.month } }
            .sumOf { it.cost ?: 0.0 }
    }

    /** Null si le fournisseur peut être utilisé, sinon la raison du blocage. */
    fun blockReason(providerId: String): String? {
        val l = limits[providerId] ?: return null
        l.maxRequestsPerDay?.let { if (requestsToday(providerId) >= it) return "limite quotidienne de $it requêtes atteinte" }
        l.maxCostPerMonth?.let { if (costThisMonth(providerId) >= it) return "budget mensuel estimé atteint" }
        return null
    }

    fun summary(providerId: String): String {
        val c = pricing[providerId]?.let { "%.2f %s (estimation)".format(costThisMonth(providerId), it.currency) } ?: "tarif non renseigné"
        return "${requestsToday(providerId)} requête(s) aujourd'hui · coût du mois : $c"
    }
}

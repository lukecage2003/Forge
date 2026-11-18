package ai.forge.core.memory

import ai.forge.core.text.TextUtils
import kotlin.math.ln

/**
 * Index lexical BM25 (sur racines françaises). Utilisé seul ou fusionné avec la similarité
 * vectorielle par Reciprocal Rank Fusion — combinaison robuste sans calibrage de scores.
 */
class Bm25Index(private val k1: Double = 1.2, private val b: Double = 0.75) {
    private val docs = mutableMapOf<String, List<String>>()
    private val df = mutableMapOf<String, Int>()

    fun put(id: String, text: String) {
        remove(id)
        val terms = TextUtils.stems(text)
        docs[id] = terms
        terms.toSet().forEach { df[it] = (df[it] ?: 0) + 1 }
    }

    fun remove(id: String) {
        docs.remove(id)?.toSet()?.forEach { t ->
            val c = (df[t] ?: 1) - 1
            if (c <= 0) df.remove(t) else df[t] = c
        }
    }

    fun search(query: String, allowed: (String) -> Boolean = { true }): List<Pair<String, Double>> {
        val q = TextUtils.stems(query).distinct()
        if (q.isEmpty() || docs.isEmpty()) return emptyList()
        val n = docs.size.toDouble()
        val avgdl = docs.values.sumOf { it.size }.toDouble() / n
        return docs.asSequence().filter { allowed(it.key) }.map { (id, terms) ->
            val tf = terms.groupingBy { it }.eachCount()
            val score = q.sumOf { term ->
                val f = tf[term] ?: return@sumOf 0.0
                val idf = ln(1 + (n - (df[term] ?: 0) + 0.5) / ((df[term] ?: 0) + 0.5))
                idf * (f * (k1 + 1)) / (f + k1 * (1 - b + b * terms.size / avgdl))
            }
            id to score
        }.filter { it.second > 0 }.sortedByDescending { it.second }.toList()
    }
}

/** Fusion de classements (RRF). */
fun reciprocalRankFusion(vararg rankings: List<String>, k: Int = 60): List<Pair<String, Double>> {
    val scores = mutableMapOf<String, Double>()
    rankings.forEach { r -> r.forEachIndexed { i, id -> scores[id] = (scores[id] ?: 0.0) + 1.0 / (k + i + 1) } }
    return scores.entries.sortedByDescending { it.value }.map { it.key to it.value }
}

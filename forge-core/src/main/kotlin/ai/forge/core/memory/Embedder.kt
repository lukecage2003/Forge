package ai.forge.core.memory

import ai.forge.core.text.TextUtils
import kotlin.math.sqrt

/**
 * Génère des vecteurs sémantiques. Sur Android, l'implémentation de production utilisera un
 * modèle d'embedding local (ex. MediaPipe Text Embedder / modèle multilingue type E5 en LiteRT).
 */
interface Embedder {
    val dimensions: Int
    suspend fun embed(text: String): FloatArray
}

/**
 * Embedder de repli par hachage de n-grammes de caractères (« hashing trick »).
 * Ce n'est PAS un embedding sémantique : il capte la proximité lexicale et morphologique,
 * utile hors-ligne et pour les tests, mais insuffisant pour la paraphrase.
 */
class HashingEmbedder(override val dimensions: Int = 512) : Embedder {
    override suspend fun embed(text: String): FloatArray {
        val v = FloatArray(dimensions)
        for (tok in TextUtils.contentTokens(text)) {
            val padded = "#${TextUtils.stem(tok)}#"
            for (n in 3..4) for (i in 0..padded.length - n) {
                val h = padded.substring(i, i + n).hashCode()
                val idx = Math.floorMod(h, dimensions)
                v[idx] += if ((h ushr 31) == 0) 1f else -1f
            }
        }
        return v.normalized()
    }
}

fun FloatArray.normalized(): FloatArray {
    val n = sqrt(fold(0.0) { a, x -> a + x * x }).toFloat()
    return if (n == 0f) this else FloatArray(size) { this[it] / n }
}

fun cosine(a: FloatArray, b: FloatArray): Double {
    require(a.size == b.size) { "Dimensions incompatibles : ${a.size} vs ${b.size}" }
    var dot = 0.0; var na = 0.0; var nb = 0.0
    for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
    return if (na == 0.0 || nb == 0.0) 0.0 else dot / (sqrt(na) * sqrt(nb))
}

package ai.forge.reasoning

import ai.forge.core.model.ChatMessage

/**
 * Garde les échanges les plus récents qui tiennent dans la fenêtre du modèle : une conversation
 * peut durer indéfiniment sans dépasser la mémoire du modèle (les plus anciens messages sortent
 * du contexte, les souvenirs importants restent dans la mémoire longue de Forge).
 */
object ContextBudget {
    /** Estimation prudente pour le français : ~3,2 caractères par jeton. */
    fun estimate(text: String): Int = (text.length / 3.2).toInt() + 4

    fun fit(turns: List<ChatMessage>, budget: Int): List<ChatMessage> {
        if (turns.isEmpty()) return turns
        val out = ArrayDeque<ChatMessage>()
        var used = 0
        for (m in turns.asReversed()) {
            val cost = estimate(m.content)
            if (out.isNotEmpty() && used + cost > budget) break
            // Le dernier message (la question) est toujours gardé, tronqué au besoin.
            if (out.isEmpty() && cost > budget) { out.addFirst(m.copy(content = m.content.takeLast((budget.coerceAtLeast(200) * 3.2).toInt()))); used = budget; continue }
            out.addFirst(m); used += cost
        }
        return out.toList()
    }
}

package ai.forge.memory

import ai.forge.core.text.TextUtils
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/** Souvenir proposé, pas encore enregistré. */
data class MemoryCandidate(
    val kind: MemoryKind,
    val text: String,
    val provenance: Provenance,
    val key: String? = null,
    val project: String? = null,
    val tags: Set<String> = emptySet(),
    val ttlMs: Long? = null,
    /** Si vrai, l'utilisateur doit valider avant l'enregistrement. */
    val needsConfirmation: Boolean = true,
)

sealed interface RememberOutcome {
    data class Stored(val item: MemoryItem) : RememberOutcome
    data class MergedIntoExisting(val item: MemoryItem) : RememberOutcome
    /** Contradiction avec un souvenir existant : la décision revient à l'utilisateur. */
    data class Conflict(val candidate: MemoryCandidate, val existing: MemoryItem, val reason: String) : RememberOutcome
    data class PendingConfirmation(val candidate: MemoryCandidate) : RememberOutcome
}

/**
 * Logique de mémoire (§4) : déduplication, contradictions, rétention, provenance, export.
 * Tout est consultable, modifiable et supprimable par l'utilisateur.
 */
class MemoryManager(
    private val store: MemoryStore,
    private val embedder: Embedder? = null,
    private val now: () -> Long = System::currentTimeMillis,
    private val duplicateThreshold: Double = 0.8,
) {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    suspend fun remember(candidate: MemoryCandidate, confirmed: Boolean = false): RememberOutcome {
        if (candidate.needsConfirmation && !confirmed) return RememberOutcome.PendingConfirmation(candidate)
        val active = store.all().filter { it.active && it.kind == candidate.kind }

        // 1. Contradiction sur une même clé sémantique.
        candidate.key?.let { key ->
            active.firstOrNull { it.key == key }?.let { existing ->
                if (normalized(existing.text) != normalized(candidate.text)) {
                    return RememberOutcome.Conflict(candidate, existing,
                        "Un souvenir existant porte sur « $key » avec une valeur différente.")
                }
            }
        }
        // 2. Contradiction probable : même énoncé, polarité inversée (négation) — testée avant la déduplication, car
        //    « j'aime X » et « je n'aime pas X » partagent les mêmes mots porteurs.
        active.firstOrNull { looksNegationOf(it.text, candidate.text) }?.let { existing ->
            return RememberOutcome.Conflict(candidate, existing, "L'énoncé semble contredire un souvenir existant.")
        }
        // 3. Doublon quasi identique : on rafraîchit l'existant plutôt que de dupliquer.
        val cStems = TextUtils.stems(candidate.text)
        active.firstOrNull { TextUtils.jaccard(TextUtils.stems(it.text), cStems) >= duplicateThreshold }?.let { dup ->
            val merged = dup.copy(updatedAtMs = now(), tags = dup.tags + candidate.tags)
            store.upsert(merged)
            return RememberOutcome.MergedIntoExisting(merged)
        }
        val item = create(candidate)
        store.upsert(item)
        return RememberOutcome.Stored(item)
    }

    /** Résout une contradiction : le nouveau souvenir remplace l'ancien (conservé pour l'historique). */
    suspend fun supersede(existing: MemoryItem, candidate: MemoryCandidate): MemoryItem {
        val item = create(candidate)
        store.upsert(item)
        store.upsert(existing.copy(supersededBy = item.id, updatedAtMs = now()))
        return item
    }

    suspend fun recall(query: MemoryQuery): List<MemoryHit> {
        val emb = embedder?.embed(query.text)
        val t = now()
        return store.search(query, emb).filter { it.item.expiresAtMs == null || it.item.expiresAtMs > t }
    }

    suspend fun edit(id: String, newText: String): MemoryItem? {
        val old = store.get(id) ?: return null
        val updated = old.copy(text = newText, updatedAtMs = now(),
            embedding = embedder?.embed(newText)?.toList(),
            provenance = old.provenance.copy(type = Provenance.Type.CORRECTION))
        store.upsert(updated)
        return updated
    }

    suspend fun forget(id: String) = store.delete(id)

    /** Purge des souvenirs expirés (hors souvenirs épinglés). Renvoie le nombre supprimé. */
    suspend fun purgeExpired(): Int {
        val t = now()
        return store.all().filter { !it.pinned && it.expiresAtMs != null && it.expiresAtMs <= t }
            .count { store.delete(it.id) }
    }

    suspend fun exportJson(): String = json.encodeToString(store.all())

    /** Bloc de contexte compact, avec provenance, à injecter dans le prompt. */
    fun formatForPrompt(hits: List<MemoryHit>): String = hits.joinToString("\n") {
        "- [${it.item.kind.label}] ${it.item.text} (source : ${it.item.provenance.type.name.lowercase()})"
    }

    private suspend fun create(c: MemoryCandidate) = MemoryItem(
        id = UUID.randomUUID().toString(),
        kind = c.kind, text = c.text.trim(), key = c.key, project = c.project, tags = c.tags,
        provenance = c.provenance, createdAtMs = now(),
        expiresAtMs = c.ttlMs?.let { now() + it },
        embedding = embedder?.embed(c.text)?.toList(),
    )

    private fun normalized(s: String) = TextUtils.contentTokens(s).joinToString(" ")

    private val negation = Regex("\\b(ne|n|pas|jamais|plus|aucun|aucune|non)\\b")
    private fun looksNegationOf(a: String, b: String): Boolean {
        val fa = TextUtils.fold(a); val fb = TextUtils.fold(b)
        val negA = negation.containsMatchIn(fa); val negB = negation.containsMatchIn(fb)
        if (negA == negB) return false
        val sa = TextUtils.stems(fa.replace(negation, " ")); val sb = TextUtils.stems(fb.replace(negation, " "))
        return sa.isNotEmpty() && TextUtils.jaccard(sa, sb) >= 0.75
    }
}

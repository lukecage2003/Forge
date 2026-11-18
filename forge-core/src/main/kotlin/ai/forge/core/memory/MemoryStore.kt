package ai.forge.core.memory

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Persistance des souvenirs. L'application Android fournit une implémentation Room (SQLite + FTS) ;
 * [InMemoryMemoryStore] sert aux tests et au mode éphémère.
 */
interface MemoryStore {
    suspend fun upsert(item: MemoryItem)
    suspend fun get(id: String): MemoryItem?
    suspend fun all(): List<MemoryItem>
    suspend fun delete(id: String): Boolean
    suspend fun search(query: MemoryQuery, queryEmbedding: FloatArray?): List<MemoryHit>
}

class InMemoryMemoryStore : MemoryStore {
    private val mutex = Mutex()
    private val items = linkedMapOf<String, MemoryItem>()
    private val bm25 = Bm25Index()

    override suspend fun upsert(item: MemoryItem) = mutex.withLock {
        items[item.id] = item
        bm25.put(item.id, item.text + " " + item.tags.joinToString(" ") + " " + (item.key ?: ""))
    }

    override suspend fun get(id: String) = mutex.withLock { items[id] }
    override suspend fun all() = mutex.withLock { items.values.toList() }
    override suspend fun delete(id: String) = mutex.withLock {
        bm25.remove(id); items.remove(id) != null
    }

    override suspend fun search(query: MemoryQuery, queryEmbedding: FloatArray?): List<MemoryHit> = mutex.withLock {
        fun eligible(it: MemoryItem) = it.kind in query.kinds &&
            (query.project == null || it.project == query.project) &&
            (query.includeSuperseded || it.active)

        val lexical = bm25.search(query.text) { id -> items[id]?.let(::eligible) == true }.map { it.first }
        val vector = if (queryEmbedding == null) emptyList() else items.values.asSequence()
            .filter(::eligible)
            .mapNotNull { m -> m.embedding?.let { m.id to cosine(queryEmbedding, it.toFloatArray()) } }
            .filter { it.second > 0.15 }
            .sortedByDescending { it.second }
            .map { it.first }.toList()

        reciprocalRankFusion(lexical, vector).take(query.limit).mapNotNull { (id, s) ->
            items[id]?.let {
                val why = buildList {
                    if (id in lexical) add("lexical")
                    if (id in vector) add("sémantique")
                }.joinToString("+")
                MemoryHit(it, s, why)
            }
        }
    }
}

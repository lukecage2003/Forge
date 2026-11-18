package ai.forge.app.memory

import ai.forge.core.memory.InMemoryMemoryStore
import ai.forge.core.memory.MemoryHit
import ai.forge.core.memory.MemoryItem
import ai.forge.core.memory.MemoryQuery
import ai.forge.core.memory.MemoryStore
import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Ligne SQLite : le souvenir complet est sérialisé en JSON (schéma stable, évolutions faciles). */
@Entity(tableName = "memory")
data class MemoryRow(
    @PrimaryKey val id: String,
    val kind: String,
    val json: String,
    val updatedAtMs: Long,
)

@Dao
interface MemoryDao {
    @Upsert suspend fun upsert(row: MemoryRow)
    @Query("SELECT * FROM memory") suspend fun all(): List<MemoryRow>
    @Query("SELECT * FROM memory WHERE id = :id") suspend fun get(id: String): MemoryRow?
    @Query("DELETE FROM memory WHERE id = :id") suspend fun delete(id: String): Int
}

@Database(entities = [MemoryRow::class], version = 1, exportSchema = false)
abstract class ForgeDatabase : RoomDatabase() {
    abstract fun memory(): MemoryDao

    companion object {
        fun open(context: Context) = Room.databaseBuilder(context, ForgeDatabase::class.java, "forge.db").build()
    }
}

/**
 * Persistance Room + index de recherche hybride en mémoire vive (BM25 + vecteurs) chargé au démarrage.
 * Adapté à quelques dizaines de milliers de souvenirs ; au-delà, prévoir sqlite-vec / FTS5 (voir docs/ROADMAP.md).
 */
class RoomMemoryStore(private val dao: MemoryDao) : MemoryStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = InMemoryMemoryStore()
    private val loadLock = Mutex()
    private var loaded = false

    private suspend fun ensureLoaded() = loadLock.withLock {
        if (!loaded) {
            dao.all().forEach { row -> runCatching { json.decodeFromString<MemoryItem>(row.json) }.onSuccess { cache.upsert(it) } }
            loaded = true
        }
    }

    override suspend fun upsert(item: MemoryItem) {
        ensureLoaded()
        dao.upsert(MemoryRow(item.id, item.kind.name, json.encodeToString(item), item.updatedAtMs))
        cache.upsert(item)
    }
    override suspend fun get(id: String): MemoryItem? { ensureLoaded(); return cache.get(id) }
    override suspend fun all(): List<MemoryItem> { ensureLoaded(); return cache.all() }
    override suspend fun delete(id: String): Boolean { ensureLoaded(); dao.delete(id); return cache.delete(id) }
    override suspend fun search(query: MemoryQuery, queryEmbedding: FloatArray?): List<MemoryHit> {
        ensureLoaded(); return cache.search(query, queryEmbedding)
    }
}

package ai.forge.app.memory

import ai.forge.memory.InMemoryMemoryStore
import ai.forge.memory.MemoryHit
import ai.forge.memory.MemoryItem
import ai.forge.memory.MemoryQuery
import ai.forge.memory.MemoryStore
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

/** Conversation enregistrée (historique). */
@Entity(tableName = "conversation")
data class ConversationRow(@PrimaryKey val id: String, val title: String, val createdAtMs: Long, val updatedAtMs: Long)

@Entity(tableName = "message", indices = [androidx.room.Index("conversationId")])
data class MessageRow(@PrimaryKey val id: String, val conversationId: String, val author: String, val text: String, val meta: String?, val atMs: Long)

/** Stockage clé/valeur JSON (scripts du studio, cours et fiches de révision…). */
@Entity(tableName = "kv", primaryKeys = ["namespace", "key"])
data class KvRow(val namespace: String, val key: String, val json: String, val updatedAtMs: Long)

@Dao
interface ConversationDao {
    @Upsert suspend fun upsert(c: ConversationRow)
    @Upsert suspend fun insert(m: MessageRow)
    @Query("SELECT * FROM conversation WHERE id = :id") suspend fun get(id: String): ConversationRow?
    @Query("SELECT * FROM conversation ORDER BY updatedAtMs DESC LIMIT :limit") suspend fun recent(limit: Int): List<ConversationRow>
    @Query("SELECT DISTINCT c.* FROM conversation c JOIN message m ON m.conversationId = c.id WHERE m.text LIKE '%' || :q || '%' OR c.title LIKE '%' || :q || '%' ORDER BY c.updatedAtMs DESC LIMIT :limit")
    suspend fun search(q: String, limit: Int): List<ConversationRow>
    @Query("SELECT * FROM message WHERE conversationId = :id ORDER BY atMs, rowid") suspend fun messages(id: String): List<MessageRow>
    @Query("DELETE FROM message WHERE conversationId = :id") suspend fun deleteMessages(id: String)
    @Query("DELETE FROM conversation WHERE id = :id") suspend fun deleteConversation(id: String)
}

@Dao
interface KvDao {
    @Upsert suspend fun put(row: KvRow)
    @Query("SELECT * FROM kv WHERE namespace = :ns ORDER BY updatedAtMs DESC") suspend fun all(ns: String): List<KvRow>
    @Query("SELECT * FROM kv WHERE namespace = :ns AND `key` = :key") suspend fun get(ns: String, key: String): KvRow?
    @Query("DELETE FROM kv WHERE namespace = :ns AND `key` = :key") suspend fun delete(ns: String, key: String)
}

@Database(entities = [MemoryRow::class, ConversationRow::class, MessageRow::class, KvRow::class], version = 2, exportSchema = false)
abstract class ForgeDatabase : RoomDatabase() {
    abstract fun memory(): MemoryDao
    abstract fun conversations(): ConversationDao
    abstract fun kv(): KvDao

    companion object {
        /** v1 → v2 : ajout de l'historique et du stockage clé/valeur, sans toucher à la mémoire. */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `conversation` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `createdAtMs` INTEGER NOT NULL, `updatedAtMs` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `message` (`id` TEXT NOT NULL, `conversationId` TEXT NOT NULL, `author` TEXT NOT NULL, `text` TEXT NOT NULL, `meta` TEXT, `atMs` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_message_conversationId` ON `message` (`conversationId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `kv` (`namespace` TEXT NOT NULL, `key` TEXT NOT NULL, `json` TEXT NOT NULL, `updatedAtMs` INTEGER NOT NULL, PRIMARY KEY(`namespace`, `key`))")
            }
        }

        fun open(context: Context) = Room.databaseBuilder(context, ForgeDatabase::class.java, "forge.db").addMigrations(MIGRATION_1_2).build()
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

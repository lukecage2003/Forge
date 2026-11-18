package ai.forge.app.studio

import ai.forge.app.memory.KvDao
import ai.forge.app.memory.KvRow
import ai.forge.editorial.ViralScript
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class SavedScript(val id: String, val topic: String, val script: ViralScript, val createdAtMs: Long, val retentionScore: String)

/** Scripts du studio, enregistrés sur le téléphone. */
class ScriptRepository(private val kv: KvDao) {
    private val json = Json { ignoreUnknownKeys = true }
    suspend fun save(topic: String, script: ViralScript, score: String): SavedScript {
        val s = SavedScript(UUID.randomUUID().toString(), topic, script, System.currentTimeMillis(), score)
        kv.put(KvRow(NS, s.id, json.encodeToString(SavedScript.serializer(), s), s.createdAtMs)); return s
    }
    suspend fun all(): List<SavedScript> = kv.all(NS).mapNotNull { runCatching { json.decodeFromString(SavedScript.serializer(), it.json) }.getOrNull() }
    suspend fun delete(id: String) = kv.delete(NS, id)
    private companion object { const val NS = "scripts" }
}

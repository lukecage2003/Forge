package ai.forge.app.history

import ai.forge.app.memory.ConversationDao
import ai.forge.app.memory.ConversationRow
import ai.forge.app.memory.MessageRow
import ai.forge.core.model.ChatMessage
import ai.forge.core.model.Role
import ai.forge.reasoning.ConversationContext
import java.util.UUID

data class StoredMessage(val author: String, val text: String, val meta: String?)
data class ConversationSummary(val id: String, val title: String, val updatedAtMs: Long)

/** Historique des conversations : tout est gardé sur le téléphone, consultable, recherchable, supprimable. */
class ConversationStore(private val dao: ConversationDao, private val now: () -> Long = System::currentTimeMillis) {

    suspend fun append(conversationId: String, author: String, text: String, meta: String? = null) {
        if (text.isBlank()) return
        val t = now()
        val existing = dao.get(conversationId)
        val title = existing?.title ?: if (author == "Vous") text.lineSequence().first().take(70) else "Conversation"
        dao.upsert(ConversationRow(conversationId, title, existing?.createdAtMs ?: t, t))
        dao.insert(MessageRow(UUID.randomUUID().toString(), conversationId, author, text, meta, t))
    }

    suspend fun recent(limit: Int = 100) = dao.recent(limit).map { ConversationSummary(it.id, it.title, it.updatedAtMs) }
    suspend fun search(q: String, limit: Int = 100) =
        if (q.isBlank()) recent(limit) else dao.search(q.trim(), limit).map { ConversationSummary(it.id, it.title, it.updatedAtMs) }
    suspend fun messages(id: String) = dao.messages(id).map { StoredMessage(it.author, it.text, it.meta) }
    suspend fun delete(id: String) { dao.deleteMessages(id); dao.deleteConversation(id) }

    /** Reconstruit le contexte de conversation (mémoire immédiate) pour reprendre là où on s'était arrêté. */
    suspend fun restore(id: String): Pair<ConversationContext, List<StoredMessage>> {
        val msgs = messages(id)
        val ctx = ConversationContext(id)
        msgs.forEach { m ->
            when (m.author) {
                "Vous" -> ctx.add(ChatMessage(Role.USER, m.text))
                "Forge" -> ctx.add(ChatMessage(Role.ASSISTANT, m.text))
            }
        }
        return ctx to msgs
    }
}

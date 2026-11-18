package ai.forge.app

import ai.forge.app.history.ConversationStore
import ai.forge.app.memory.ForgeDatabase
import ai.forge.core.model.Role
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HistoryTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** Mise à jour de l'app : la base v1 (mémoire seule) migre vers v2 sans perdre un souvenir. */
    @Test fun migrationKeepsExistingMemories() = runBlocking {
        val file = ctx.getDatabasePath("migr.db").apply { parentFile?.mkdirs(); delete() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE IF NOT EXISTS `memory` (`id` TEXT NOT NULL, `kind` TEXT NOT NULL, `json` TEXT NOT NULL, `updatedAtMs` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            db.execSQL("INSERT INTO memory VALUES ('m1', 'PERSONAL', '{}', 1)")
            db.version = 1
        }
        val room = Room.databaseBuilder(ctx, ForgeDatabase::class.java, file.absolutePath).addMigrations(ForgeDatabase.MIGRATION_1_2).allowMainThreadQueries().build()
        assertEquals("m1", room.memory().all().single().id)
        room.conversations().recent(10)          // les nouvelles tables existent et sont valides
        room.close()
    }

    @Test fun conversationsAreSavedSearchableAndResumable() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ctx, ForgeDatabase::class.java).allowMainThreadQueries().build()
        var t = 0L
        val store = ConversationStore(db.conversations()) { ++t }
        store.append("c1", "Vous", "Parle-moi du vibranium"); store.append("c1", "Forge", "Le vibranium absorbe les vibrations.")
        store.append("c2", "Vous", "Idée de Short sur Thor")
        assertEquals(listOf("c2", "c1"), store.recent().map { it.id })
        assertEquals("Parle-moi du vibranium", store.recent().last().title)
        assertEquals(listOf("c1"), store.search("absorbe").map { it.id })
        val (context, msgs) = store.restore("c1")
        assertEquals(2, msgs.size); assertEquals(listOf(Role.USER, Role.ASSISTANT), context.turns.map { it.role })
        store.delete("c1"); assertTrue(store.search("vibranium").isEmpty())
        db.close()
    }
}

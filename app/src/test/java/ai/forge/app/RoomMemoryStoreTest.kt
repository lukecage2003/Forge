package ai.forge.app

import ai.forge.app.memory.ForgeDatabase
import ai.forge.app.memory.RoomMemoryStore
import ai.forge.memory.*
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Scénario B (mémoire durable) : un souvenir survit à la « fermeture » de l'app (nouvelle instance du store). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomMemoryStoreTest {
    @Test fun memoriesPersistAcrossStoreInstancesAndDeletionIsDurable() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(ctx, ForgeDatabase::class.java).allowMainThreadQueries().build()
        val m1 = MemoryManager(RoomMemoryStore(db.memory()), HashingEmbedder())
        val stored = m1.remember(MemoryCandidate(MemoryKind.PROJECT, "Décision : le script vibranium sort en Short d'abord",
            Provenance(Provenance.Type.USER_EXPLICIT, "conv"), project = "primestone", needsConfirmation = false)) as RememberOutcome.Stored
        m1.remember(MemoryCandidate(MemoryKind.PERSONAL, "Code temporaire du studio", Provenance(Provenance.Type.USER_EXPLICIT, "conv"), needsConfirmation = false))

        // « Plusieurs jours plus tard » : nouvelle instance, même base.
        val m2 = MemoryManager(RoomMemoryStore(db.memory()), HashingEmbedder())
        val hits = m2.recall(MemoryQuery("Où en est le script sur le vibranium ?", project = "primestone"))
        assertEquals(stored.item.id, hits.first().item.id)

        val toForget = m2.recall(MemoryQuery("code studio")).first().item.id
        m2.forget(toForget)
        val m3 = MemoryManager(RoomMemoryStore(db.memory()), HashingEmbedder())
        assertTrue(m3.recall(MemoryQuery("code studio", limit = 10)).none { it.item.id == toForget })
        db.close()
    }
}

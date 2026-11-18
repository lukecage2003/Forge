package ai.forge.research

import kotlin.test.*
import kotlinx.coroutines.test.runTest

class SourcesTest {    @Test fun sourceClassificationAndCorroboration() {
        val c = SourceClassifier()
        assertEquals(SourceType.COMMUNITY, c.classify("https://marvel.fandom.com/wiki/Vibranium"))
        assertEquals(SourceType.PRIMARY, c.classify("https://www.marvel.com/comics/issue/1"))
        assertEquals(SourceType.INSTITUTIONAL, c.classify("https://www.insee.fr/fr/statistiques"))
        assertEquals(SourceType.UNVERIFIED, c.classify("pas une url"))
        c.learn("monblog.fr", SourceType.SPECIALIZED)
        assertEquals(SourceType.SPECIALIZED, c.classify("https://monblog.fr/article"))

        val cites = listOf(
            Citation("https://lemonde.fr/a", "a", SourceType.JOURNALISTIC, accessedAtIso = "2026-11-18"),
            Citation("https://www.lemonde.fr/b", "b", SourceType.JOURNALISTIC, accessedAtIso = "2026-11-18"),
        )
        val a = Corroboration.assess(cites, requiresPrimary = false, recentTopic = true)
        assertEquals(1, a.independentSources)
        assertFalse(a.sufficient)
        assertTrue(a.note.contains("revérifier"))
    }

}

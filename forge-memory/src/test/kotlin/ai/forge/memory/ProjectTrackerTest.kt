package ai.forge.memory

import kotlin.test.*

class ProjectTrackerTest {
    private val t = ProjectTracker()

    @Test fun detectsDeclaredProjects() {
        assertEquals("Wakanda Files", t.detect("Mon projet s'appelle Wakanda Files, une série sur le vibranium")?.project)
        assertEquals("Les Rois Oubliés", t.detect("Je travaille sur « Les Rois Oubliés » en ce moment")?.project)
        assertEquals("Comics Décryptés", t.detect("ma chaîne YouTube s'appelle Comics Décryptés")?.project)
    }

    @Test fun notesProgressOnKnownProjectsOnly() {
        assertEquals("Primestone", t.detect("Pour primestone j'ai fini le montage de la vidéo Black Panther")?.project)
        assertNull(t.detect("Primestone, c'est quoi déjà ?"))                      // simple mention : pas de note
        assertEquals("Primestone", t.mentioned("Primestone, c'est quoi déjà ?", emptySet()))
        assertEquals("Wakanda Files", t.detect("Wakanda Files : il me reste deux scripts", setOf("Wakanda Files"))?.project)
    }

    @Test fun ignoresOrdinarySentences() {
        assertNull(t.detect("Je travaille sur ma motivation"))
        assertNull(t.detect("Quelle heure est-il ?"))
        assertNull(t.detect("Je vais sur Marvel Wiki pour vérifier"))
    }
}

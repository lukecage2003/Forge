package ai.forge.voice

import ai.forge.voice.RecognitionPolicy.Action
import kotlin.test.*

class VoiceRobustnessTest {
    @Test fun benignErrorsAreSilentAndRetriedWithBackoff() {
        assertEquals(Action.Retry(150), RecognitionPolicy.decide(RecognitionPolicy.NO_MATCH, 0, true))
        val r1 = RecognitionPolicy.decide(RecognitionPolicy.CLIENT, 0, true) as Action.Retry
        val r2 = RecognitionPolicy.decide(RecognitionPolicy.CLIENT, 2, true) as Action.Retry
        assertTrue(r2.delayMs > r1.delayMs)
        assertIs<Action.Recreate>(RecognitionPolicy.decide(RecognitionPolicy.CLIENT, 3, true))
        assertIs<Action.Retry>(RecognitionPolicy.decide(RecognitionPolicy.SERVER_DISCONNECTED, 1, true))
    }

    @Test fun missingFrenchPackSwitchesEngineThenExplains() {
        assertEquals(Action.SwitchToStandardEngine, RecognitionPolicy.decide(RecognitionPolicy.LANGUAGE_UNAVAILABLE, 0, onDeviceEngine = true))
        assertTrue((RecognitionPolicy.decide(RecognitionPolicy.LANGUAGE_UNAVAILABLE, 0, false) as Action.Stop).message.contains("français"))
        assertIs<Action.Stop>(RecognitionPolicy.decide(RecognitionPolicy.PERMISSIONS, 0, true))
        assertIs<Action.Stop>(RecognitionPolicy.decide(42, 5, true))
    }

    @Test fun sameUtteranceIsNotProcessedTwice() {
        var t = 0L
        val d = UtteranceDeduplicator(now = { t })
        assertTrue(d.accept("Parle-moi de Superman"))
        t = 300; assertFalse(d.accept("parle moi de superman"))
        t = 600; assertFalse(d.accept("Parle-moi de Superman."))
        t = 5_000; assertTrue(d.accept("Parle-moi de Superman"))
        assertFalse(d.accept("   "))
    }

    @Test fun halfDuplexUnlessHeadset() {
        assertFalse(DuplexPolicy.listenWhileSpeaking(headsetConnected = false, userEnabledBargeIn = false))
        assertTrue(DuplexPolicy.listenWhileSpeaking(headsetConnected = true, userEnabledBargeIn = false))
    }

    @Test fun wakeWordDetectionToleratesTranscriptionErrors() {
        assertEquals("quelle heure est-il ?", WakeWord.strip("Forge, quelle heure est-il ?"))
        assertEquals("parle-moi de Thor", WakeWord.strip("Hey forje parle-moi de Thor"))
        assertEquals("", WakeWord.strip("Forge !"))
        assertNull(WakeWord.strip("Je parlais à mon frère"))
        assertNull(WakeWord.strip("La forgerie est fermée"))
    }

    @Test fun vocabularyFixesComicsNamesAndLearns() {
        val v = Vocabulary()
        assertEquals("T'Challa porte un costume en vibranium au Wakanda", v.correct("tchala porte un costume en vibra nium au wakhanda"))
        assertEquals("Black Panther contre Darkseid", v.correct("black panter contre dark side"))
        assertEquals("Le Spider-Man de Primestone Media", v.correct("Le spider man de prime stone media"))
        assertEquals("La blackpantherie", v.correct("La blackpantherie"))   // pas de remplacement au milieu d'un mot
        v.learn("nakia", "Nakia"); v.learn("en kia", "Nakia")
        assertEquals("Nakia aime T'Challa", v.correct("en kia aime tchala"))
        assertTrue(Vocabulary(v.learnedPairs()).correct("nakia").contains("Nakia"))
    }
}

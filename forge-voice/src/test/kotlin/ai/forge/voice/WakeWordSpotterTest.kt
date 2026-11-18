package ai.forge.voice

import kotlin.test.*

class WakeWordSpotterTest {
    @Test fun triggersOnConfidentForgeOnlyAndRespectsCooldown() {
        var t = 0L
        val s = WakeWordSpotter(strictness = 0.6, cooldownMs = 2_000) { t }
        assertTrue(s.onResult("""{"result":[{"conf":0.93,"word":"forge"}],"text":"forge"}"""))
        t = 500; assertFalse(s.onResult("""{"result":[{"conf":0.95,"word":"forge"}],"text":"forge"}"""))   // délai
        t = 5_000; assertFalse(s.onResult("""{"result":[{"conf":0.4,"word":"forge"}],"text":"forge"}""")) // peu sûr
        assertFalse(s.onResult("""{"result":[{"conf":0.99,"word":"forger"}],"text":"forger"}"""))           // leurre
        assertFalse(s.onResult("""{"text":"[unk] porte"}"""))
        assertFalse(s.onResult("pas du json"))
        assertTrue(s.onResult("""{"text":"forge"}"""))                                                     // sans confiances
        assertTrue(WakeWordSpotter.GRAMMAR.contains("\"[unk]\""))
    }

    @Test fun partialTriggersOnlyWhenForgeIsFirstWord() {
        var t = 0L
        val s = WakeWordSpotter(strictness = 0.55) { t }
        assertFalse(s.onPartial("""{"partial":"[unk] forge"}"""))
        assertTrue(s.onPartial("""{"partial":"forge"}"""))
        t = 100; assertFalse(s.onResult("""{"result":[{"conf":0.9,"word":"forge"}],"text":"forge"}"""))  // même appel, pas de doublon
        assertFalse(WakeWordSpotter(strictness = 0.9).onPartial("""{"partial":"forge"}"""))           // mode strict : on attend la confiance
    }
}

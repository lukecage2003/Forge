package ai.forge.editorial

import kotlin.test.*

class MotionTest {
    private val script = ViralScript(Platform.TIKTOK, listOf("T"), null, emptyList(), listOf(
        ScriptBeat("0:00", 0, "masque", "UNE FAILLE ?", "Le costume a une faille."),
        ScriptBeat("0:03", 3, "impact", null, "Il absorbe 1000 impacts par minute sans broncher du tout."),
        ScriptBeat("0:06", 6, "masque", "LA FAILLE", "La faille, c'est lui."),
    ), emptyList())

    @Test fun specFromScriptUsesBeatsTimingAndStyles() {
        val spec = MotionSpec.fromScript(script)
        assertEquals(1080, spec.width); assertEquals(1920, spec.height)
        assertEquals(listOf(3000L, 3000L), spec.scenes.take(2).map { it.durationMs })
        assertEquals(MotionStyle.IMPACT, spec.scenes[0].style)
        assertEquals(MotionStyle.COUNTER, spec.scenes[1].style)
        assertEquals(spec.totalMs * 30 / 1000, spec.frameCount.toLong())
    }

    @Test fun timelineFadesAnimatesAndCounts() {
        val spec = MotionSpec.fromScript(script)
        val start = MotionTimeline.frameAt(spec, 0)
        assertEquals(0, start.sceneIndex); assertTrue(start.opacity < 0.05f); assertTrue(start.scale > 1.3f)
        val mid = MotionTimeline.frameAt(spec, 1_500)
        assertEquals(1f, mid.opacity, 0.01f); assertEquals(1f, mid.scale, 0.05f)
        val counting = MotionTimeline.frameAt(spec, 3_300)
        assertEquals(1, counting.sceneIndex); assertTrue(counting.text.startsWith("Il absorbe ") && !counting.text.contains("1000"))
        val counted = MotionTimeline.frameAt(spec, 5_900)
        assertTrue(counted.text.contains("1000"))
        assertEquals(2, MotionTimeline.frameAt(spec, 999_999).sceneIndex)
    }
}

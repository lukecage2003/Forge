package ai.forge.editorial

import ai.forge.core.text.TextUtils
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Styles d'animation originaux (texte et formes générés : aucun élément protégé). */
enum class MotionStyle { KINETIC, TYPEWRITER, IMPACT, COUNTER }

data class MotionScene(
    val text: String,
    val subtitle: String? = null,
    val durationMs: Long,
    val style: MotionStyle = MotionStyle.KINETIC,
)

data class MotionSpec(val scenes: List<MotionScene>, val width: Int, val height: Int, val fps: Int = 30) {
    val totalMs: Long get() = scenes.sumOf { it.durationMs }
    val frameCount: Int get() = (totalMs * fps / 1000).toInt()

    companion object {
        /** Cartons animés à partir d'un script : un carton par plan, texte à l'écran (ou début de la phrase). */
        fun fromScript(s: ViralScript): MotionSpec {
            val vertical = s.platform.vertical
            val starts = s.beats.map { it.seconds ?: 0 }
            val scenes = s.beats.mapIndexed { i, b ->
                val next = starts.getOrNull(i + 1) ?: (starts[i] + max(2, TextUtils.wordCount(b.narration) * 60 / s.platform.wordsPerMinute))
                val dur = ((next - starts[i]).coerceIn(1, 12) * 1000L)
                val text = b.onScreenText ?: TextUtils.sentences(b.narration).firstOrNull()?.split(" ")?.take(7)?.joinToString(" ") ?: b.narration.take(40)
                val style = when {
                    i == 0 -> MotionStyle.IMPACT
                    Regex("\\d{2,}").containsMatchIn(text) -> MotionStyle.COUNTER
                    text.length > 28 -> MotionStyle.TYPEWRITER
                    else -> MotionStyle.KINETIC
                }
                MotionScene(text, null, dur, style)
            }
            return MotionSpec(scenes, if (vertical) 1080 else 1920, if (vertical) 1920 else 1080)
        }
    }
}

/** État d'une image de l'animation : calcul pur, rendu par la plateforme (Canvas Android). */
data class MotionFrame(
    val sceneIndex: Int,
    val text: String,
    val subtitle: String?,
    val style: MotionStyle,
    /** Progression dans la scène 0..1. */
    val progress: Float,
    val opacity: Float,
    val offsetY: Float,
    val scale: Float,
    /** Phase globale pour l'arrière-plan. */
    val backgroundPhase: Float,
)

object MotionTimeline {
    private const val IN_MS = 350f
    private const val OUT_MS = 250f

    fun easeOutCubic(t: Float) = 1f - (1f - t).pow(3)
    fun easeOutBack(t: Float): Float { val c1 = 1.70158f; val c3 = c1 + 1; return 1 + c3 * (t - 1).pow(3) + c1 * (t - 1).pow(2) }

    fun frameAt(spec: MotionSpec, tMs: Long): MotionFrame {
        var acc = 0L
        val idx = spec.scenes.indexOfFirst { acc += it.durationMs; tMs < acc }.let { if (it < 0) spec.scenes.lastIndex else it }
        val scene = spec.scenes[idx]
        val start = spec.scenes.take(idx).sumOf { it.durationMs }
        val local = (tMs - start).coerceIn(0, scene.durationMs).toFloat()
        val inT = min(1f, local / IN_MS); val outT = min(1f, max(0f, (scene.durationMs - local) / OUT_MS))
        val opacity = min(easeOutCubic(inT), outT)
        val (offset, scale) = when (scene.style) {
            MotionStyle.IMPACT -> 0f to (1.35f - 0.35f * easeOutBack(inT))
            MotionStyle.KINETIC -> (1f - easeOutCubic(inT)) * 80f to 1f
            MotionStyle.TYPEWRITER, MotionStyle.COUNTER -> 0f to 1f
        }
        val shown = when (scene.style) {
            MotionStyle.TYPEWRITER -> scene.text.take((scene.text.length * min(1f, local / (scene.durationMs * 0.6f))).toInt().coerceAtLeast(1))
            MotionStyle.COUNTER -> Regex("\\d+").replace(scene.text) { m ->
                val target = m.value.toLong(); (target * easeOutCubic(min(1f, local / (scene.durationMs * 0.7f)))).toLong().toString()
            }
            else -> scene.text
        }
        return MotionFrame(idx, shown, scene.subtitle, scene.style, local / scene.durationMs, opacity, offset, scale,
            (tMs % 20_000L) / 20_000f * 6.2831855f)
    }
}

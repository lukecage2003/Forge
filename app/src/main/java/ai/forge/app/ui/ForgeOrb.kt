package ai.forge.app.ui

import ai.forge.voice.PresenceState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Présence de Forge (§12.1) : une sphère de lumière vivante.
 * Respire en veille, s'ouvre quand elle écoute (suit le niveau sonore réel), tourbillonne quand
 * elle réfléchit, pulse quand elle parle, passe au rouge en cas d'erreur.
 */
@Composable
fun ForgeOrb(state: PresenceState, audioLevel: Float, modifier: Modifier = Modifier, size: Dp = 72.dp) {
    val core by animateColorAsState(when (state) {
        PresenceState.STANDBY -> ForgePalette.GoldDeep
        PresenceState.LISTENING -> ForgePalette.Listening
        PresenceState.UNDERSTANDING, PresenceState.THINKING -> ForgePalette.Gold
        PresenceState.RESPONDING, PresenceState.EXECUTING -> ForgePalette.GoldSoft
        PresenceState.ERROR -> ForgePalette.Error
    }, tween(600), label = "core")
    val t = rememberInfiniteTransition(label = "orb")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(when (state) {
        PresenceState.THINKING, PresenceState.UNDERSTANDING -> 1_600; PresenceState.EXECUTING -> 1_000; else -> 9_000 }, easing = LinearEasing)), label = "spin")
    val breath by t.animateFloat(0.94f, 1.04f, infiniteRepeatable(tween(2_400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath")
    val wave by t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(1_400, easing = LinearEasing)), label = "wave")
    val level by animateFloatAsState(if (state == PresenceState.LISTENING || state == PresenceState.RESPONDING) audioLevel else 0f, tween(120), label = "level")

    Canvas(modifier.size(size).semantics { contentDescription = "Forge : ${state.label}" }) {
        val base = this.size.minDimension / 2f
        val pulse = if (state == PresenceState.RESPONDING) 1f + 0.05f * sin(wave * 2) else 1f
        val r = base * 0.62f * breath * pulse * (1f + 0.22f * level)
        // Halo.
        drawCircle(Brush.radialGradient(listOf(core.copy(alpha = 0.45f), core.copy(alpha = 0.10f), Color.Transparent), center, base), base)
        // Corps : sphère lumineuse avec reflet décentré.
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.95f), core, core.copy(alpha = 0.85f), ForgePalette.AuroraEmber.copy(alpha = 0.9f)),
            Offset(center.x - r * 0.35f, center.y - r * 0.4f), r * 1.6f), r)
        // Anneau tournant (dégradé conique) : réflexion / exécution.
        rotate(spin) {
            drawCircle(Brush.sweepGradient(listOf(Color.Transparent, core.copy(alpha = 0.9f), Color.White, Color.Transparent), center),
                r * 1.22f, style = Stroke(width = base * 0.035f))
        }
        // Ondes sonores en écoute.
        if (state == PresenceState.LISTENING) for (i in 0 until 3) {
            val k = ((wave / (2 * PI).toFloat()) + i / 3f) % 1f
            drawCircle(core.copy(alpha = (1f - k) * 0.35f), r * (1.2f + k * 0.6f), style = Stroke(width = 1.5f))
        }
        // Particules orbitales en réflexion.
        if (state == PresenceState.THINKING || state == PresenceState.UNDERSTANDING) for (i in 0 until 4) {
            val a = Math.toRadians((spin * 1.5 + i * 90).toDouble())
            drawCircle(Color.White.copy(alpha = 0.85f), base * 0.035f, Offset(center.x + cos(a).toFloat() * r * 1.38f, center.y + sin(a).toFloat() * r * 1.38f))
        }
    }
}

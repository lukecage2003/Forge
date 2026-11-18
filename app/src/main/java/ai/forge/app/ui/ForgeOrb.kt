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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Représentation animée de Forge (§12.1). Compacte par défaut pour ne pas monopoliser l'écran.
 * Le rayon suit le niveau sonore réel (micro) quand il est disponible.
 */
@Composable
fun ForgeOrb(state: PresenceState, audioLevel: Float, modifier: Modifier = Modifier, size: Dp = 72.dp) {
    val color by animateColorAsState(when (state) {
        PresenceState.STANDBY -> ForgePalette.TextMuted
        PresenceState.LISTENING -> ForgePalette.Listening
        PresenceState.UNDERSTANDING -> ForgePalette.GoldSoft
        PresenceState.THINKING, PresenceState.RESPONDING, PresenceState.EXECUTING -> ForgePalette.Gold
        PresenceState.ERROR -> ForgePalette.Error
    }, label = "orbColor")
    val t = rememberInfiniteTransition(label = "orb")
    val phase by t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(
        when (state) { PresenceState.THINKING -> 1_200; PresenceState.EXECUTING -> 800; else -> 3_000 }, easing = LinearEasing)), label = "phase")
    val breath by t.animateFloat(0.92f, 1.04f, infiniteRepeatable(tween(1_800), RepeatMode.Reverse), label = "breath")
    val level by animateFloatAsState(if (state == PresenceState.LISTENING || state == PresenceState.RESPONDING) audioLevel else 0f, label = "level")

    Canvas(modifier.size(size).semantics { contentDescription = "Forge : ${state.label}" }) {
        val r = this.size.minDimension / 2f * (if (state == PresenceState.STANDBY) 0.8f else breath) * (0.85f + 0.25f * level)
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.55f), Color.Transparent), center, r * 1.4f), r * 1.4f)
        drawCircle(Brush.radialGradient(listOf(color, color.copy(alpha = 0.25f)), center, r), r * 0.72f)
        // Arcs orbitaux : réflexion / exécution.
        if (state == PresenceState.THINKING || state == PresenceState.EXECUTING || state == PresenceState.UNDERSTANDING) {
            for (i in 0 until 3) {
                val a = phase + i * (2 * PI / 3).toFloat()
                drawCircle(color, radius = r * 0.08f, center = Offset(center.x + cos(a) * r * 0.95f, center.y + sin(a) * r * 0.95f))
            }
        }
        drawCircle(color.copy(alpha = 0.6f), r * 0.98f, style = Stroke(width = 1.5f))
    }
}

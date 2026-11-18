package ai.forge.app.island

import ai.forge.app.ui.ForgeOrb
import ai.forge.app.ui.ForgePalette
import ai.forge.voice.PresenceState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.sin

/** Contenu de la bulle, alimenté par la session vocale. */
data class IslandState(
    val presence: PresenceState,
    val level: Float,
    val heard: String,
    val reply: String,
    val expanded: Boolean,
)

/**
 * « Île » Forge : capsule noire en haut de l'écran, autour de la caméra frontale.
 * Compacte = veille ; élargie = écoute (onde qui suit la voix) ; grande = réponse en cours.
 */
@Composable
fun ForgeIsland(s: IslandState, onTap: () -> Unit, onDismiss: () -> Unit, compactHeight: androidx.compose.ui.unit.Dp = 30.dp) {
    val speaking = s.presence == PresenceState.RESPONDING && s.reply.isNotBlank()
    val listening = s.presence == PresenceState.LISTENING || s.presence == PresenceState.UNDERSTANDING
    // Compacte : à peine plus large que la caméra ; élargie : limitée à la largeur de l'écran.
    val width = when { !s.expanded -> 104.dp; speaking -> 380.dp; else -> 320.dp }
    val glow by animateFloatAsState(if (listening) 0.35f + 0.65f * s.level else if (s.expanded) 0.35f else 0f, tween(120), label = "glow")
    val shape = RoundedCornerShape(if (s.expanded) 30.dp else compactHeight / 2)

    Box(Modifier.padding(top = if (s.expanded) 6.dp else 3.dp).padding(horizontal = 8.dp), contentAlignment = Alignment.TopCenter) {
        Box(
            Modifier
                .animateContentSize(spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow))
                .widthIn(max = width).width(width)
                .then(if (s.expanded) Modifier.heightIn(min = 36.dp) else Modifier.height(compactHeight))
                .clip(shape)
                .background(Color.Black)
                .border(1.dp, Brush.horizontalGradient(listOf(ForgePalette.Gold.copy(alpha = glow * 0.9f), ForgePalette.AuroraEmber.copy(alpha = glow * 0.6f),
                    ForgePalette.Gold.copy(alpha = glow * 0.9f))), shape)
                .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }
                .pointerInput(Unit) { detectVerticalDragGestures { _, drag -> if (drag < -12) onDismiss() } }
                .padding(horizontal = if (s.expanded) 12.dp else 8.dp, vertical = if (s.expanded) 10.dp else 3.dp),
        ) {
            if (!s.expanded) Row(Modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                // Orbe à gauche, onde à droite : le centre reste libre pour la caméra.
                ForgeOrb(s.presence, s.level, size = 18.dp)
                Spacer(Modifier.weight(1f))
                Waveform(s.level, active = listening, bars = 4, modifier = Modifier.height(12.dp).width(24.dp))
            } else Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ForgeOrb(s.presence, s.level, size = 30.dp)
                    Spacer(Modifier.width(10.dp))
                    AnimatedContent(label(s.presence), transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) }, label = "label") {
                        Text(it, color = ForgePalette.GoldSoft, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.weight(1f))
                    Waveform(s.level, active = listening || speaking, bars = 9, modifier = Modifier.height(22.dp).width(70.dp))
                }
                val body = if (speaking) s.reply else s.heard
                if (body.isNotBlank()) Text(
                    if (speaking) body.takeLast(220) else "« ${body.takeLast(140)} »",
                    Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp),
                    color = if (speaking) Color.White else Color.White.copy(alpha = 0.85f), fontSize = 14.5.sp, lineHeight = 20.sp,
                    maxLines = if (speaking) 4 else 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun label(p: PresenceState) = when (p) {
    PresenceState.LISTENING -> "Je vous écoute"
    PresenceState.UNDERSTANDING -> "Je vous écoute"
    PresenceState.THINKING -> "Je réfléchis…"
    PresenceState.RESPONDING -> "Forge"
    PresenceState.EXECUTING -> "J'exécute…"
    PresenceState.ERROR -> "Un problème est survenu"
    PresenceState.STANDBY -> "Forge"
}

/** Onde dorée : barres qui suivent le niveau sonore réel, avec un léger mouvement organique. */
@Composable
private fun Waveform(level: Float, active: Boolean, bars: Int, modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "wave")
    val phase by t.animateFloat(0f, 6.2831855f, androidx.compose.animation.core.infiniteRepeatable(tween(900, easing = androidx.compose.animation.core.LinearEasing)), label = "ph")
    val lvl by animateFloatAsState(if (active) level else 0.05f, tween(90), label = "lvl")
    Canvas(modifier) {
        val gap = size.width / (bars * 2f)
        for (i in 0 until bars) {
            val shapeK = 1f - abs(i - (bars - 1) / 2f) / ((bars + 1) / 2f)
            val wobble = 0.55f + 0.45f * sin(phase + i * 0.9f)
            val h = (size.height * (0.18f + 0.82f * lvl * shapeK * wobble)).coerceIn(size.height * 0.15f, size.height)
            val x = gap * (2 * i + 0.5f)
            drawRoundRect(Brush.verticalGradient(listOf(ForgePalette.GoldSoft, ForgePalette.Gold, ForgePalette.AuroraEmber), startY = (size.height - h) / 2, endY = (size.height + h) / 2),
                topLeft = Offset(x, (size.height - h) / 2), size = Size(gap, h), cornerRadius = CornerRadius(gap / 2, gap / 2))
        }
    }
}

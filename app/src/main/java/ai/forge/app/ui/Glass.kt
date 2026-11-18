package ai.forge.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/*
 * Verre dépoli sans dépendance externe.
 * L'arrière-plan est une « aurore » procédurale : chaque panneau de verre redessine la portion
 * d'aurore située derrière lui, floutée par le GPU (RenderEffect, Android 12+), puis ajoute
 * une teinte, un liseré lumineux et un reflet supérieur — l'effet « Liquid Glass ».
 */

/** Phase d'animation partagée (0..2π) et taille de l'écran : toutes les vitres voient la même aurore. */
class AuroraState(val phase: State<Float>) { var rootSize by mutableStateOf(Size.Zero) }

val LocalAurora = compositionLocalOf<AuroraState?> { null }

/** Dessine l'aurore : quelques halos colorés qui dérivent lentement sur le noir. */
fun DrawScope.drawAurora(size: Size, phase: Float) {
    if (size.width <= 0f) return
    drawRect(ForgePalette.Black, size = size)
    val w = size.width; val h = size.height; val r = maxOf(w, h)
    fun blob(color: Color, cx: Float, cy: Float, radius: Float, alpha: Float) =
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = alpha), Color.Transparent), Offset(cx, cy), radius), radius, Offset(cx, cy))
    blob(ForgePalette.AuroraIndigo, w * (0.15f + 0.10f * sin(phase)), h * (0.18f + 0.06f * cos(phase * 0.7f)), r * 0.55f, 0.55f)
    blob(ForgePalette.AuroraGold, w * (0.85f + 0.08f * cos(phase * 0.8f)), h * (0.30f + 0.08f * sin(phase * 0.6f)), r * 0.45f, 0.42f)
    blob(ForgePalette.AuroraEmber, w * (0.70f + 0.12f * sin(phase * 0.5f)), h * (0.82f + 0.05f * cos(phase)), r * 0.50f, 0.35f)
    blob(ForgePalette.AuroraTeal, w * (0.20f + 0.10f * cos(phase * 0.9f)), h * (0.75f + 0.07f * sin(phase * 0.4f)), r * 0.40f, 0.30f)
    // Voile sombre pour garder le texte lisible.
    drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.10f), Color.Black.copy(alpha = 0.45f))), size = size)
}

/** Fond animé de l'application ; fournit l'état d'aurore aux vitres. */
@Composable
fun AuroraBackground(content: @Composable BoxScope.() -> Unit) {
    val t = rememberInfiniteTransition(label = "aurora")
    val phase = t.animateFloat(0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(28_000, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    val state = remember { AuroraState(phase) }
    CompositionLocalProvider(LocalAurora provides state) {
        Box(Modifier.fillMaxSize().onGloballyPositioned { state.rootSize = Size(it.size.width.toFloat(), it.size.height.toFloat()) }) {
            Canvas(Modifier.fillMaxSize()) { drawAurora(size, state.phase.value) }
            content()
        }
    }
}

/**
 * Surface de verre. [tint] règle l'opacité laiteuse ; [highlight] le liseré lumineux.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    tint: Color = Color.White.copy(alpha = 0.075f),
    blur: Dp = 32.dp,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onClick: (() -> Unit)? = null,
    /** false = fond figé (pas redessiné à chaque image) : à utiliser dans les listes, bien plus léger. */
    live: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val aurora = LocalAurora.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier
            .onGloballyPositioned { origin = it.positionInRoot() }
            .clip(shape)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
    ) {
        if (aurora != null) {
            Canvas(Modifier.matchParentSize().blur(blur, BlurredEdgeTreatment.Rectangle)) {
                translate(-origin.x, -origin.y) { drawAurora(aurora.rootSize, if (live) aurora.phase.value else 0f) }
            }
        }
        Box(Modifier.matchParentSize().background(tint))
        // Reflet : la lumière vient d'en haut.
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.10f), Color.Transparent, Color.Transparent))))
        Box(Modifier.matchParentSize().border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.32f), Color.White.copy(alpha = 0.05f), Color.White.copy(alpha = 0.12f))), shape))
        Box(Modifier.padding(contentPadding), content = content)
    }
}

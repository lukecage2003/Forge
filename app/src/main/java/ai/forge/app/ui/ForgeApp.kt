package ai.forge.app.ui

import ai.forge.app.AppContainer
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp

data class ChatLine(val author: String, val text: String, val meta: String? = null, val pendingAction: ai.forge.security.ToolCall? = null,
    val specialist: String? = null, val request: String? = null, val rated: Boolean = false)

private data class Tab(val id: String, val icon: ImageVector, val label: String)
private val tabs = listOf(
    Tab("chat", Icons.Default.AutoAwesome, "Forge"),
    Tab("studio", Icons.Default.Movie, "Studio"),
    Tab("academy", Icons.Default.School, "Études"),
    Tab("library", Icons.AutoMirrored.Filled.MenuBook, "Comics"),
    Tab("more", Icons.Default.GridView, "Plus"),
)

/** Hauteur réservée à la barre d'onglets flottante (les écrans s'arrêtent au-dessus). */
val TabBarSpace = 92.dp

@Composable
fun ForgeApp(container: AppContainer) {
    ForgeTheme {
        AuroraBackground {
            var screen by rememberSaveable { mutableStateOf("chat") }
            AnimatedContent(screen, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) }, label = "screen") { s ->
                Box(Modifier.fillMaxSize().statusBarsPadding()) {
                    val ask: (String) -> Unit = { text -> container.prefill.value = text; screen = "chat" }
                    when (s) {
                        "chat" -> ChatScreen(container, onSettings = { screen = "more" })
                        "studio" -> StudioScreen(container, ask)
                        "academy" -> AcademyScreen(container, ask)
                        "library" -> LibraryScreen(container)
                        else -> MoreScreen(container)
                    }
                }
            }
            GlassTabBar(screen, onSelect = { screen = it }, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun GlassTabBar(selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    GlassSurface(
        modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp).height(64.dp).fillMaxWidth(),
        shape = RoundedCornerShape(32.dp), tint = Color.White.copy(alpha = 0.06f),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            tabs.forEach { t ->
                val on = t.id == selected
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(24.dp))
                        .background(if (on) Color.White.copy(alpha = 0.12f) else Color.Transparent)
                        .clickable(remember { MutableInteractionSource() }, null) { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onSelect(t.id) }
                        .padding(vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(t.icon, t.label, tint = if (on) ForgePalette.Gold else ForgePalette.TextMuted, modifier = Modifier.size(22.dp))
                    Text(t.label, style = MaterialTheme.typography.labelSmall, color = if (on) ForgePalette.Gold else ForgePalette.TextMuted)
                }
            }
        }
    }
}

/** En-tête de page façon iOS : grand titre, sous-titre discret. */
@Composable
fun LargeTitle(title: String, subtitle: String? = null, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.displaySmall, color = ForgePalette.Text)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = ForgePalette.TextMuted) }
        }
        trailing()
    }
}

/** Bouton capsule en verre (actions secondaires). */
@Composable
fun GlassPill(text: String, icon: ImageVector? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    GlassSurface(modifier, shape = CircleShape, onClick = onClick, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            icon?.let { Icon(it, null, tint = ForgePalette.GoldSoft, modifier = Modifier.size(18.dp)) }
            Text(text, style = MaterialTheme.typography.labelLarge, color = ForgePalette.Text)
        }
    }
}

/** Bouton principal : capsule dorée lumineuse. */
@Composable
fun GoldButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier.clip(CircleShape)
            .background(androidx.compose.ui.graphics.Brush.horizontalGradient(
                if (enabled) listOf(ForgePalette.GoldSoft, ForgePalette.Gold, ForgePalette.GoldDeep) else listOf(Color.Gray, Color.DarkGray)))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.labelLarge, color = ForgePalette.Black) }
}

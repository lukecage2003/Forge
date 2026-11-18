package ai.forge.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Charte FORGE (§12) : noir profond, or lumineux, verre dépoli.
 * Inspiration « Liquid Glass » : surfaces translucides, lumière colorée qui traverse le verre,
 * typographie grande et serrée pour les titres, contrastes lisibles (AA) pour le texte.
 */
object ForgePalette {
    val Black = Color(0xFF040406)
    val Surface = Color(0xFF111114)
    val SurfaceHigh = Color(0xFF1B1B20)
    val Gold = Color(0xFFF2C14E)
    val GoldSoft = Color(0xFFF7D98B)
    val GoldDeep = Color(0xFFC8901A)
    val Text = Color(0xFFF4F4F6)
    val TextMuted = Color(0xFFA1A1AA)
    val Listening = Color(0xFF7EE0B5)
    val Error = Color(0xFFFF7A6E)
    // Lumières de l'aurore d'arrière-plan.
    val AuroraGold = Color(0xFFE8A93A)
    val AuroraEmber = Color(0xFFB4441C)
    val AuroraIndigo = Color(0xFF3B2F9E)
    val AuroraTeal = Color(0xFF0F6E80)
}

private val scheme = darkColorScheme(
    primary = ForgePalette.Gold, onPrimary = ForgePalette.Black,
    secondary = ForgePalette.GoldSoft, background = ForgePalette.Black, onBackground = ForgePalette.Text,
    surface = ForgePalette.Surface, onSurface = ForgePalette.Text, surfaceVariant = ForgePalette.SurfaceHigh,
    onSurfaceVariant = ForgePalette.TextMuted, error = ForgePalette.Error, outline = Color.White.copy(alpha = 0.18f),
)

private val typography = Typography(
    displayLarge = TextStyle(fontSize = 44.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.03).em, lineHeight = 48.sp),
    displaySmall = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.025).em, lineHeight = 40.sp),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.015).em),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.01).em),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.5.sp, lineHeight = 25.sp, letterSpacing = 0.005.em),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.02.em),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.03.em),
)

@Composable
fun ForgeTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme, typography = typography, content = content)

package ai.forge.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Charte FORGE (§12) : noir profond, or en accent, contrastes AA pour la lisibilité. */
object ForgePalette {
    val Black = Color(0xFF050506)
    val Surface = Color(0xFF111114)
    val SurfaceHigh = Color(0xFF1B1B20)
    val Gold = Color(0xFFD4A017)
    val GoldSoft = Color(0xFFE9C46A)
    val Text = Color(0xFFECECEC)
    val TextMuted = Color(0xFF9A9AA2)
    val Listening = Color(0xFF7FD1AE)
    val Error = Color(0xFFE5736B)
}

private val scheme = darkColorScheme(
    primary = ForgePalette.Gold, onPrimary = ForgePalette.Black,
    secondary = ForgePalette.GoldSoft, background = ForgePalette.Black, onBackground = ForgePalette.Text,
    surface = ForgePalette.Surface, onSurface = ForgePalette.Text, surfaceVariant = ForgePalette.SurfaceHigh,
    error = ForgePalette.Error,
)

@Composable
fun ForgeTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)

package ai.forge.app.ui

import ai.forge.app.AppContainer
import ai.forge.models.DownloadableModel
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Accueil quand Forge n'a pas encore de « cerveau » : explication simple, choix clair, un bouton. */
@Composable
fun BrainCard(container: AppContainer, onOpenSettings: () -> Unit) {
    val state by container.brain.collectAsState()
    if (state == AppContainer.BrainState.Ready) return
    var choice by remember { mutableStateOf(DownloadableModel.RECOMMENDED) }
    var confirmMobile by remember { mutableStateOf(false) }

    GlassSurface(Modifier.fillMaxWidth().padding(bottom = 12.dp), contentPadding = PaddingValues(18.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (val s = state) {
                is AppContainer.BrainState.Downloading -> {
                    Text("Installation du cerveau…", style = MaterialTheme.typography.titleLarge, color = ForgePalette.Gold)
                    GlassProgress(if (s.total > 0) s.downloaded.toFloat() / s.total else 0f)
                    Text("${s.downloaded / 1_000_000} / ${s.total / 1_000_000} Mo · gardez Forge ouvert. En cas de coupure, l'installation reprend où elle s'est arrêtée.",
                        style = MaterialTheme.typography.bodySmall, color = ForgePalette.TextMuted)
                }
                else -> {
                    Text("Installez le cerveau de Forge", style = MaterialTheme.typography.titleLarge, color = ForgePalette.Text)
                    Text("Une seule fois. Ensuite Forge répond directement sur votre téléphone, même sans connexion.",
                        style = MaterialTheme.typography.bodyMedium, color = ForgePalette.TextMuted)
                    ModelChoices(choice) { choice = it }
                    (s as? AppContainer.BrainState.Error)?.let { Text(it.message, color = ForgePalette.Error, style = MaterialTheme.typography.bodySmall) }
                    if (confirmMobile) {
                        Text("Vous n'êtes pas en Wi-Fi : ${gigas(choice.sizeBytes / 1e9)} Go seront pris sur vos données mobiles.",
                            color = ForgePalette.Error, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GoldButton("Continuer") { confirmMobile = false; container.installBrain(choice) }
                            GlassPill("Attendre le Wi-Fi") { confirmMobile = false }
                        }
                    } else GoldButton(if (s is AppContainer.BrainState.Error) "Reprendre l'installation" else "Installer (${gigas(choice.sizeBytes / 1e9)} Go)",
                        Modifier.fillMaxWidth()) { if (container.onMeteredNetwork()) confirmMobile = true else container.installBrain(choice) }
                    Text("J'ai un PC avec Ollama ou une clé d'API → Réglages", style = MaterialTheme.typography.labelMedium, color = ForgePalette.GoldSoft,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickableNoRipple(onOpenSettings).padding(4.dp))
                }
            }
        }
    }
}

/** Liste des modèles disponibles (catalogue libre). */
@Composable
fun ModelChoices(selected: DownloadableModel, onSelect: (DownloadableModel) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DownloadableModel.CATALOG.take(2).forEach { m ->
            val on = m == selected
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                .background(if (on) ForgePalette.Gold.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.04f))
                .clickableNoRipple { onSelect(m) }.padding(14.dp)) {
                Box(Modifier.padding(top = 3.dp).size(14.dp).clip(CircleShape).background(if (on) ForgePalette.Gold else Color.White.copy(alpha = 0.2f)))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(m.displayName, style = MaterialTheme.typography.titleMedium, color = ForgePalette.Text)
                    Text("${m.tagline} · ${gigas(m.sizeBytes / 1e9)} Go · licence ${m.license}", style = MaterialTheme.typography.bodySmall, color = ForgePalette.TextMuted)
                }
            }
        }
    }
}

@Composable
fun GlassProgress(fraction: Float) {
    Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.1f))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(CircleShape)
            .background(Brush.horizontalGradient(listOf(ForgePalette.GoldSoft, ForgePalette.Gold))))
    }
}

fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = this.then(
    Modifier.clickable(interactionSource = null, indication = null, onClick = onClick))

/** Taille en Go au format français (« 3,3 »). */
fun gigas(v: Double): String = String.format(java.util.Locale.FRANCE, "%.1f", v)

package ai.forge.app.ui

import ai.forge.app.AppContainer
import ai.forge.models.DownloadableModel
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Accueil quand Forge n'a pas encore de « cerveau » (modèle de langage).
 * Explication simple, une seule action principale, aucune notion technique imposée.
 */
@Composable
fun BrainCard(container: AppContainer, onOpenSettings: () -> Unit) {
    val state by container.brain.collectAsState()
    if (state == AppContainer.BrainState.Ready) return
    val model = DownloadableModel.RECOMMENDED
    var confirmMobile by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth().padding(12.dp), colors = CardDefaults.cardColors(containerColor = ForgePalette.SurfaceHigh)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (val s = state) {
                is AppContainer.BrainState.Downloading -> {
                    Text("Installation du cerveau de Forge…", style = MaterialTheme.typography.titleMedium, color = ForgePalette.Gold)
                    val f = if (s.total > 0) s.downloaded.toFloat() / s.total else 0f
                    LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth(), color = ForgePalette.Gold)
                    Text("${s.downloaded / 1_000_000} / ${s.total / 1_000_000} Mo — gardez l'application ouverte. En cas de coupure, le téléchargement reprendra où il s'est arrêté.",
                        style = MaterialTheme.typography.bodySmall, color = ForgePalette.TextMuted)
                }
                else -> {
                    Text("Forge a besoin de son cerveau", style = MaterialTheme.typography.titleMedium, color = ForgePalette.Gold)
                    Text("L'application contient la voix, la mémoire et les écrans. Le « cerveau » qui comprend et répond " +
                        "(un modèle de langage) est un fichier trop lourd pour être inclus : il s'installe une seule fois, " +
                        "puis Forge fonctionne sur votre téléphone, même sans Internet.")
                    Text("${model.displayName} · ${model.sizeBytes / 1_000_000} Mo · licence libre ${model.license}",
                        style = MaterialTheme.typography.bodySmall, color = ForgePalette.TextMuted)
                    (s as? AppContainer.BrainState.Error)?.let { Text(it.message, color = ForgePalette.Error) }
                    if (confirmMobile) {
                        Text("Vous n'êtes pas en Wi-Fi : ${model.sizeBytes / 1_000_000} Mo seront pris sur vos données mobiles.", color = ForgePalette.Error)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { confirmMobile = false; container.installRecommendedBrain() }) { Text("Continuer quand même") }
                            OutlinedButton(onClick = { confirmMobile = false }) { Text("Attendre le Wi-Fi") }
                        }
                    } else {
                        Button(onClick = { if (container.onMeteredNetwork()) confirmMobile = true else container.installRecommendedBrain() },
                            modifier = Modifier.fillMaxWidth()) {
                            Text(if (s is AppContainer.BrainState.Error) "Reprendre l'installation" else "Installer le cerveau de Forge")
                        }
                    }
                    TextButton(onClick = onOpenSettings) { Text("J'ai un PC avec Ollama ou une clé d'API → Réglages") }
                }
            }
        }
    }
}

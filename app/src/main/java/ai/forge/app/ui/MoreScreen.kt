package ai.forge.app.ui

import ai.forge.app.AppContainer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** « Plus » : Mémoire, Actions, Réglages. */
@Composable
fun MoreScreen(container: AppContainer) {
    var sub by remember { mutableStateOf<String?>(null) }
    when (sub) {
        null -> Column(Modifier.fillMaxSize()) {
            LargeTitle("Plus")
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Entry(Icons.Default.Psychology, "Mémoire", "Ce que Forge retient, à consulter ou effacer") { sub = "memory" }
                Entry(Icons.Default.Bolt, "Actions", "Journal des actions et arrêt d'urgence") { sub = "actions" }
                Entry(Icons.Default.Tune, "Réglages", "Cerveau, voix, recherche, confidentialité") { sub = "settings" }
            }
        }
        else -> Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(start = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { sub = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour", tint = ForgePalette.Text) }
            }
            when (sub) { "memory" -> MemoryScreen(container); "actions" -> ActionCenterScreen(container); else -> SettingsScreen(container) }
        }
    }
}

@Composable
private fun Entry(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    GlassSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), live = false, onClick = onClick) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = ForgePalette.Gold, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(14.dp))
            Column { Text(title, style = MaterialTheme.typography.titleMedium, color = ForgePalette.Text); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = ForgePalette.TextMuted) }
        }
    }
}

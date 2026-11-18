package ai.forge.app.ui

import ai.forge.app.AppContainer
import ai.forge.memory.MemoryItem
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Mémoire (§4) : consultable, supprimable, exportable par l'utilisateur. */
@Composable
fun MemoryScreen(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<MemoryItem>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    suspend fun reload() { items = container.memory.list() }
    LaunchedEffect(Unit) { reload() }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            val json = container.memory.exportJson()
            context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
            message = "Mémoire exportée (${items.size} souvenirs)."
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Mémoire", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { export.launch("forge-memoire.json") }) { Text("Exporter") }
        }
        Text("Forge ne retient que ce que vous lui demandez de retenir. Tout est supprimable.",
            style = MaterialTheme.typography.bodySmall, color = ForgePalette.TextMuted)
        message?.let { Text(it, color = ForgePalette.Gold) }
        Spacer(Modifier.height(8.dp))
        if (items.isEmpty()) Text("Aucun souvenir.", color = ForgePalette.TextMuted)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.id }) { m ->
                Card(colors = CardDefaults.cardColors(containerColor = ForgePalette.Surface)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(m.text)
                            Text("${m.kind.label}${m.project?.let { " · projet $it" } ?: ""} · source : ${m.provenance.type.name.lowercase()} · " +
                                DateFormat.getDateInstance().format(Date(m.updatedAtMs)),
                                style = MaterialTheme.typography.labelSmall, color = ForgePalette.TextMuted)
                        }
                        IconButton(onClick = { scope.launch { container.memory.forget(m.id); reload(); message = "Souvenir supprimé." } }) {
                            Icon(Icons.Default.Delete, "Supprimer ce souvenir")
                        }
                    }
                }
            }
        }
    }
}

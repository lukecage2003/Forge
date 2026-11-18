package ai.forge.app.ui

import ai.forge.app.AppContainer
import ai.forge.app.history.ConversationSummary
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Historique : toutes vos conversations, recherche dans leur contenu, reprise en un toucher. */
@Composable
fun HistoryDialog(container: AppContainer, onOpen: (String) -> Unit, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<ConversationSummary>>(emptyList()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(query) { items = container.history.search(query) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        AuroraBackground {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                LargeTitle("Historique", "${items.size} conversation(s)") {
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Fermer", tint = ForgePalette.Text) }
                }
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text("Rechercher dans vos conversations") },
                    shape = RoundedCornerShape(20.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = ForgePalette.Gold, unfocusedBorderColor = Color.White.copy(alpha = 0.15f)))
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(items, key = { it.id }) { c ->
                        GlassSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), live = false, onClick = { onOpen(c.id) }) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(c.title, style = MaterialTheme.typography.titleMedium, color = ForgePalette.Text, maxLines = 2)
                                    Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(c.updatedAtMs)),
                                        style = MaterialTheme.typography.labelSmall, color = ForgePalette.TextMuted)
                                }
                                IconButton(onClick = { scope.launch { container.history.delete(c.id); items = container.history.search(query) } }) {
                                    Icon(Icons.Default.Delete, "Supprimer", tint = ForgePalette.TextMuted)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

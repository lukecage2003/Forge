package ai.forge.app.ui

import ai.forge.app.AppContainer
import ai.forge.documents.IndexStatus
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Bibliothèque comics : import de fichiers choisis par l'utilisateur, indexation OCR sur l'appareil. */
@Composable
fun LibraryScreen(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<String?>(null) }
    var files by remember { mutableStateOf(container.library.index.all()) }

    fun displayName(uri: Uri): String = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: uri.lastPathSegment ?: "fichier.cbz"

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        container.scope.launch {
            uris.forEachIndexed { i, uri ->
                val name = displayName(uri)
                progress = "Indexation ${i + 1}/${uris.size} : $name"
                runCatching {
                    container.library.indexLocal(context.contentResolver, uri, name) { p, n -> progress = "$name — page $p/$n" }
                }.onFailure { progress = "Échec : ${it.message}" }
            }
            files = container.library.index.all()
            container.refreshTools()
            progress = container.library.summary()
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Bibliothèque", style = MaterialTheme.typography.titleLarge)
        Text("Choisissez des fichiers CBZ : ils sont lus et indexés sur le téléphone, rien n'est envoyé ailleurs.",
            style = MaterialTheme.typography.bodySmall, color = ForgePalette.TextMuted)
        Spacer(Modifier.height(8.dp))
        Button(onClick = { picker.launch(arrayOf("application/zip", "application/x-cbz", "application/vnd.comicbook+zip", "application/octet-stream")) }) {
            Text("Ajouter des comics (CBZ)")
        }
        progress?.let { Text(it, color = ForgePalette.Gold, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(files, key = { it.fileId }) { f ->
                Card(colors = CardDefaults.cardColors(containerColor = ForgePalette.Surface)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(f.reference.display())
                        Text(when (f.status) {
                            IndexStatus.INDEXED -> "${f.pageCount} pages indexées"
                            IndexStatus.FAILED -> "Échec : ${f.error}"
                            else -> "En attente"
                        }, style = MaterialTheme.typography.labelSmall, color = if (f.status == IndexStatus.FAILED) ForgePalette.Error else ForgePalette.TextMuted)
                        TextButton(onClick = { scope.launch { container.library.forget(f.fileId); files = container.library.index.all(); container.refreshTools() } }) {
                            Text("Retirer de l'index")
                        }
                    }
                }
            }
        }
    }
}

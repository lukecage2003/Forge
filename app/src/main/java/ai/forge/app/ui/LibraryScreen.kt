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

    Column(Modifier.fillMaxSize()) {
        LargeTitle("Comics", "Votre bibliothèque, lue sur le téléphone.")
        Column(Modifier.padding(horizontal = 16.dp)) {
            GoldButton("Ajouter des comics (CBZ)", Modifier.fillMaxWidth()) {
                picker.launch(arrayOf("application/zip", "application/x-cbz", "application/vnd.comicbook+zip", "application/octet-stream"))
            }
            Text("Les fichiers restent sur votre téléphone. Forge lit les pages et vous indique toujours le numéro et la page.",
                Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = ForgePalette.TextMuted)
            progress?.let { Text(it, color = ForgePalette.Gold, modifier = Modifier.padding(top = 8.dp)) }
        }
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = TabBarSpace + 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(files, key = { it.fileId }) { f ->
                GlassSurface(Modifier.fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp), live = false) {
                    Column(Modifier.padding(14.dp)) {
                        Text(f.reference.display(), style = MaterialTheme.typography.titleMedium, color = ForgePalette.Text)
                        Text(when (f.status) {
                            IndexStatus.INDEXED -> "${f.pageCount} pages indexées"
                            IndexStatus.FAILED -> "Échec : ${f.error}"
                            else -> "En attente"
                        }, style = MaterialTheme.typography.labelSmall, color = if (f.status == IndexStatus.FAILED) ForgePalette.Error else ForgePalette.TextMuted)
                        Text("Retirer de l'index", Modifier.padding(top = 6.dp).clickableNoRipple {
                            scope.launch { container.library.forget(f.fileId); files = container.library.index.all(); container.refreshTools() }
                        }, style = MaterialTheme.typography.labelMedium, color = ForgePalette.GoldSoft)
                    }
                }
            }
        }
    }
}

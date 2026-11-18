package ai.forge.app.ui

import ai.forge.app.AppContainer
import ai.forge.comics.ComicReference
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Ajout d'une page de comic comme preuve : l'utilisateur indique la référence exacte
 * (c'est ce qui en fait une preuve vérifiée), Forge lit le texte de la page sur l'appareil.
 */
@Composable
fun PageImportDialog(container: AppContainer, image: Uri, onDone: (String) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var issue by remember { mutableStateOf("") }
    var year by remember { mutableStateOf("") }
    var page by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val fieldColors = OutlinedTextFieldDefaults.colors(focusedBorderColor = ForgePalette.Gold, unfocusedBorderColor = Color.White.copy(alpha = 0.15f))

    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = ForgePalette.SurfaceHigh,
        shape = RoundedCornerShape(28.dp),
        title = { Text("Ajouter une page comme preuve", color = ForgePalette.Text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Indiquez la référence exacte : Forge citera cette page dans vos débats.", style = MaterialTheme.typography.bodySmall, color = ForgePalette.TextMuted)
                OutlinedTextField(title, { title = it }, label = { Text("Titre (ex. Black Panther)") }, singleLine = true, colors = fieldColors)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(issue, { issue = it }, label = { Text("N°") }, singleLine = true, modifier = Modifier.weight(1f), colors = fieldColors)
                    OutlinedTextField(year, { year = it.filter(Char::isDigit).take(4) }, label = { Text("Année") }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = fieldColors)
                    OutlinedTextField(page, { page = it.filter(Char::isDigit).take(4) }, label = { Text("Page") }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = fieldColors)
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = ForgePalette.Gold)
            }
        },
        confirmButton = {
            GoldButton(if (busy) "Lecture…" else "Ajouter", enabled = !busy && title.isNotBlank() && page.isNotBlank()) {
                busy = true
                scope.launch {
                    val ref = ComicReference(title.trim(), issue = issue.trim().ifBlank { null }, year = year.toIntOrNull(), page = page.toIntOrNull())
                    val msg = runCatching {
                        val bytes = context.contentResolver.openInputStream(image)!!.use { it.readBytes() }
                        val text = container.library.addUserPage(bytes, ref)
                        container.refreshTools()
                        "Page ajoutée comme preuve vérifiée : ${ref.display()}" + if (text.isBlank()) " (aucun texte lisible : décrivez la scène dans votre message)." else "."
                    }.getOrElse { "Impossible de lire l'image : ${it.message}" }
                    busy = false
                    onDone(msg)
                }
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Annuler", color = ForgePalette.TextMuted) } },
    )
}

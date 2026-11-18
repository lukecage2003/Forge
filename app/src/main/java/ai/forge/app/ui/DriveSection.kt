package ai.forge.app.ui

import ai.forge.app.AppContainer
import ai.forge.app.drive.DriveAuth
import ai.forge.app.drive.DriveConnector
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Google Drive : connexion (lecture seule), recherche d'un comic, indexation à la demande. */
@Composable
fun DriveSection(container: AppContainer, onIndexed: () -> Unit) {
    val activity = LocalContext.current as Activity
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf(container.driveToken) }
    var status by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<DriveConnector.DriveFile>>(emptyList()) }
    val auth = remember { DriveAuth(activity) }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        when (val o = auth.handleConsentResult(r.data)) {
            is DriveAuth.Outcome.Token -> { token = o.accessToken; container.driveToken = o.accessToken; status = "Drive connecté." }
            is DriveAuth.Outcome.Error -> status = o.message
            else -> Unit
        }
    }
    fun connector() = DriveConnector({ token ?: error("non connecté") }, java.io.File(activity.cacheDir, "drive"))

    GlassSurface(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(22.dp), live = false, contentPadding = PaddingValues(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Google Drive", style = MaterialTheme.typography.titleMedium, color = ForgePalette.Text)
            if (token == null) {
                Text("Lecture seule : Forge ne modifie jamais vos fichiers. Nécessite un identifiant Google configuré (voir le guide d'installation).",
                    style = MaterialTheme.typography.bodySmall, color = ForgePalette.TextMuted)
                GlassPill("Connecter Google Drive", Icons.Default.CloudDownload) {
                    scope.launch {
                        when (val o = auth.authorize(fullReadOnly = true)) {
                            is DriveAuth.Outcome.Token -> { token = o.accessToken; container.driveToken = o.accessToken; status = "Drive connecté." }
                            is DriveAuth.Outcome.NeedsConsent -> consent.launch(IntentSenderRequest.Builder(o.launch.intentSender).build())
                            is DriveAuth.Outcome.Error -> status = "Connexion impossible : ${o.message}. Vérifiez l'identifiant OAuth Android (docs/SETUP.md)."
                        }
                    }
                }
            } else {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Nom du comic ou du dossier") },
                    trailingIcon = { IconButton(onClick = {
                        scope.launch { results = runCatching { connector().searchByName(query).filter { it.name.endsWith(".cbz", true) || it.name.endsWith(".pdf", true) } }
                            .getOrElse { status = "Recherche impossible : ${it.message}"; emptyList() } }
                    }) { Icon(Icons.Default.Search, "Rechercher") } },
                    shape = RoundedCornerShape(16.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = ForgePalette.Gold, unfocusedBorderColor = Color.White.copy(alpha = 0.15f)))
                results.take(20).forEach { f ->
                    Row {
                        Text(f.name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = ForgePalette.Text)
                        Text("Indexer", Modifier.clickableNoRipple {
                            container.scope.launch {
                                status = "Téléchargement et lecture de ${f.name}…"
                                status = runCatching { container.library.indexDriveFile(connector(), f) { p, n -> status = "${f.name} — page $p/$n" }; container.refreshTools(); onIndexed(); "${f.name} indexé." }
                                    .getOrElse { "Échec : ${it.message}" }
                            }
                        }, style = MaterialTheme.typography.labelMedium, color = ForgePalette.GoldSoft)
                    }
                }
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ForgePalette.Gold) }
        }
    }
}

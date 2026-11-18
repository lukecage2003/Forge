package ai.forge.app.ui

import ai.forge.academy.Course
import ai.forge.academy.Flashcard
import ai.forge.app.AppContainer
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

/** Études : cours importés, fiches, révision espacée, explications et interrogation orale. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AcademyScreen(container: AppContainer, onAsk: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var courses by remember { mutableStateOf<List<Course>>(emptyList()) }
    var due by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var progress by remember { mutableStateOf<String?>(null) }
    var reviewing by remember { mutableStateOf<Course?>(null) }
    suspend fun reload() { courses = container.academy.courses(); due = courses.associate { it.id to container.academy.due(it.id).size } }
    LaunchedEffect(Unit) { reload() }

    fun nameOf(uri: Uri) = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }?.substringBeforeLast('.') ?: "Cours"
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) container.scope.launch {
            progress = "Lecture du cours…"
            progress = runCatching {
                val c = container.academy.importPdf(context, uri, nameOf(uri)) { p, n -> progress = "Lecture de la page $p/$n…" }
                container.refreshTools(); reload()
                "« ${c.title} » importé : ${container.academy.cards(c.id).size} fiche(s) créée(s)."
            }.getOrElse { "Import impossible : ${it.message}" }
        }
    }

    Column(Modifier.fillMaxSize()) {
        LargeTitle("Études", "Vos cours, vos fiches, vos révisions.")
        Column(Modifier.padding(horizontal = 16.dp)) {
            GoldButton("Importer un cours (PDF)", Modifier.fillMaxWidth()) { picker.launch(arrayOf("application/pdf")) }
            Text("Le cours est lu sur le téléphone. Forge s'en sert en priorité et signale ce qui vient d'ailleurs.",
                Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = ForgePalette.TextMuted)
            progress?.let { Text(it, Modifier.padding(top = 8.dp), color = ForgePalette.Gold) }
        }
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = TabBarSpace + 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(courses, key = { it.id }) { c ->
                GlassSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), live = false) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(c.title, style = MaterialTheme.typography.titleMedium, color = ForgePalette.Text)
                        Text("${c.chunks.map { it.page }.distinct().size} pages · ${due[c.id] ?: 0} fiche(s) à réviser aujourd'hui",
                            style = MaterialTheme.typography.labelSmall, color = ForgePalette.TextMuted)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            GlassPill("Réviser", Icons.Default.Style) { reviewing = c }
                            GlassPill("M'interroger", Icons.Default.RecordVoiceOver) { onAsk("Fais-moi réviser mon cours « ${c.title} » : pose-moi une question à la fois et corrige-moi.") }
                            GlassPill("Expliquer", Icons.Default.School) { onAsk("Dans mon cours « ${c.title} », explique-moi simplement la notion de ") }
                            GlassPill("Fiche PDF", Icons.Default.PictureAsPdf) { onAsk("Fais-moi un PDF de fiche de révision du cours « ${c.title} »") }
                            GlassPill("Retirer", Icons.Default.Delete) { scope.launch { container.academy.delete(c.id); reload() } }
                        }
                    }
                }
            }
        }
    }
    reviewing?.let { c -> ReviewDialog(container, c, onClose = { reviewing = null; scope.launch { reload() } }) }
}

@Composable
private fun ReviewDialog(container: AppContainer, course: Course, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var queue by remember { mutableStateOf<List<Flashcard>?>(null) }
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(course.id) { queue = container.academy.due(course.id) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        AuroraBackground {
            Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                LargeTitle("Révision", course.title) { IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Fermer", tint = ForgePalette.Text) } }
                val card = queue?.firstOrNull()
                when {
                    queue == null -> CircularProgressIndicator(color = ForgePalette.Gold)
                    card == null -> Text("Rien à réviser aujourd'hui. Revenez demain : les fiches reviennent au bon moment.",
                        Modifier.padding(24.dp), textAlign = TextAlign.Center, color = ForgePalette.TextMuted)
                    else -> {
                        Text("${queue!!.size} fiche(s) restante(s)", style = MaterialTheme.typography.labelMedium, color = ForgePalette.TextMuted)
                        Spacer(Modifier.height(16.dp))
                        GlassSurface(Modifier.fillMaxWidth().heightIn(min = 260.dp), contentPadding = PaddingValues(22.dp), onClick = { revealed = true }) {
                            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                Text(card.front, style = MaterialTheme.typography.headlineSmall, color = ForgePalette.Text)
                                if (revealed) {
                                    Text(card.back, style = MaterialTheme.typography.bodyLarge, color = ForgePalette.GoldSoft)
                                    card.courseRef?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = ForgePalette.TextMuted) }
                                } else Text("Touchez pour voir la réponse", color = ForgePalette.TextMuted)
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        if (revealed) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("À revoir" to 1, "Difficile" to 3, "Bien" to 4, "Facile" to 5).forEach { (label, g) ->
                                GlassPill(label) {
                                    scope.launch { container.academy.review(course.id, card, g); queue = queue!!.drop(1) + if (g < 3) listOf(card) else emptyList(); revealed = false }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

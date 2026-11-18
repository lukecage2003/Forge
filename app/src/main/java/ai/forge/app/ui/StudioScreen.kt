package ai.forge.app.ui

import ai.forge.app.AppContainer
import ai.forge.app.export.DocumentExporter
import ai.forge.app.studio.SavedScript
import ai.forge.editorial.MotionSpec
import ai.forge.editorial.ProductionPlanner
import ai.forge.editorial.ScriptStudio
import ai.forge.writing.DocFormat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Studio : vos scripts, prêts à exporter (PDF, Word, sous-titres, animation). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StudioScreen(container: AppContainer, onAsk: (String) -> Unit) {
    var scripts by remember { mutableStateOf<List<SavedScript>>(emptyList()) }
    var open by remember { mutableStateOf<SavedScript?>(null) }
    LaunchedEffect(Unit) { scripts = container.scripts.all() }
    Column(Modifier.fillMaxSize()) {
        LargeTitle("Studio", "Vos scripts TikTok, Shorts et YouTube.")
        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassPill("Nouveau TikTok", Icons.Default.Add) { onAsk("Écris un script TikTok de 45 secondes sur ") }
            GlassPill("Nouveau YouTube", Icons.Default.Add) { onAsk("Écris un script YouTube de 10 minutes sur ") }
            GlassPill("Analyser le marché", Icons.Default.Insights) { onAsk("Analyse le marché YouTube sur ") }
        }
        if (scripts.isEmpty()) Text("Aucun script pour l'instant. Demandez-en un à Forge : il sera rangé ici automatiquement.",
            Modifier.padding(20.dp), color = ForgePalette.TextMuted)
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = TabBarSpace + 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(scripts, key = { it.id }) { s ->
                GlassSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), live = false, onClick = { open = s }) {
                    Column(Modifier.padding(14.dp)) {
                        Text(s.script.titles.firstOrNull() ?: s.topic, style = MaterialTheme.typography.titleMedium, color = ForgePalette.Text)
                        Text("${s.script.platform.label} · ~${s.script.estimatedSeconds} s · rétention ${s.retentionScore} · " +
                            DateFormat.getDateInstance().format(Date(s.createdAtMs)), style = MaterialTheme.typography.labelSmall, color = ForgePalette.TextMuted)
                    }
                }
            }
        }
    }
    open?.let { s -> ScriptDetail(container, s, onClose = { open = null }, onDeleted = { open = null; scripts = scripts - s }) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScriptDetail(container: AppContainer, s: SavedScript, onClose: () -> Unit, onDeleted: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    val md = remember(s) { ScriptStudio.toMarkdown(s.script) }
    val title = s.script.titles.firstOrNull() ?: s.topic
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        AuroraBackground {
            Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 40.dp)) {
                LargeTitle(title, s.script.platform.label) { IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Fermer", tint = ForgePalette.Text) } }
                FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassPill("Copier", Icons.Default.ContentCopy) { clipboard.setText(AnnotatedString(md)); status = "Copié." }
                    GlassPill("PDF", Icons.Default.PictureAsPdf) { scope.launch { val u = DocumentExporter.export(context, DocFormat.PDF, title, md); DocumentExporter.open(context, u, DocFormat.PDF) } }
                    GlassPill("Word", Icons.Default.Description) { scope.launch { val u = DocumentExporter.export(context, DocFormat.DOCX, title, md); DocumentExporter.open(context, u, DocFormat.DOCX) } }
                    GlassPill("Sous-titres SRT", Icons.Default.Subtitles) {
                        scope.launch {
                            val srt = ProductionPlanner.plan(ScriptStudio.toDocument(s.script, s.topic)).subtitlesSrt
                            val u = DocumentExporter.exportRaw(context, "${title.take(40)}.srt", "application/x-subrip", srt.toByteArray())
                            DocumentExporter.share(context, u, "application/x-subrip"); status = "Sous-titres enregistrés dans Téléchargements/Forge."
                        }
                    }
                    GlassPill("Animation MP4", Icons.Default.Movie) {
                        container.scope.launch {
                            status = "Création de l'animation…"
                            status = runCatching {
                                val u = ai.forge.app.motion.MotionExporter.export(context, MotionSpec.fromScript(s.script), "forge-${System.currentTimeMillis()}") { p -> status = "Animation… ${(p * 100).toInt()} %" }
                                DocumentExporter.share(context, u, "video/mp4"); "Animation enregistrée dans Films/Forge."
                            }.getOrElse { "Échec : ${it.message}" }
                        }
                    }
                    GlassPill("Supprimer", Icons.Default.Delete) { scope.launch { container.scripts.delete(s.id); onDeleted() } }
                }
                status?.let { Text(it, Modifier.padding(16.dp), color = ForgePalette.Gold) }
                GlassSurface(Modifier.padding(16.dp).fillMaxWidth(), live = false, contentPadding = PaddingValues(16.dp)) {
                    Text(RichText.render(md.replace(Regex("(?m)^\\\\|.*\\\\|$\\\\n?"), "")), color = ForgePalette.Text, style = MaterialTheme.typography.bodyMedium)
                }
                GlassSurface(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), live = false, contentPadding = PaddingValues(16.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        s.script.beats.forEach { b ->
                            Column {
                                Text("${b.timecode ?: ""} · ${b.visual.orEmpty()}", style = MaterialTheme.typography.labelMedium, color = ForgePalette.GoldSoft)
                                b.onScreenText?.let { Text("Écran : $it", style = MaterialTheme.typography.labelSmall, color = ForgePalette.TextMuted) }
                                Text(b.narration, style = MaterialTheme.typography.bodyLarge, color = ForgePalette.Text)
                            }
                        }
                    }
                }
            }
        }
    }
}

package ai.forge.app.ui

import ai.forge.app.AppContainer
import ai.forge.app.voice.VoiceSessionService
import ai.forge.core.model.AutonomyLevel
import ai.forge.reasoning.OrchestratorEvent
import ai.forge.voice.VoiceState
import ai.forge.voice.PresenceSignal
import ai.forge.voice.PresenceState
import ai.forge.core.model.DataClass
import ai.forge.core.model.TaskCategory
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch


data class ChatLine(val author: String, val text: String, val meta: String? = null, val pendingAction: ai.forge.security.ToolCall? = null)

@Composable
fun ForgeApp(container: AppContainer) {
    ForgeTheme {
        var screen by remember { mutableStateOf("chat") }
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                NavigationBar(containerColor = ForgePalette.Surface) {
                    listOf("chat" to (Icons.Default.Forum to "Parler"), "memory" to (Icons.Default.Psychology to "Mémoire"),
                        "library" to (Icons.AutoMirrored.Filled.MenuBook to "Comics"), "actions" to (Icons.Default.Bolt to "Actions"),
                        "settings" to (Icons.Default.Tune to "Réglages")).forEach { (id, v) ->
                        NavigationBarItem(selected = screen == id, onClick = { screen = id },
                            icon = { Icon(v.first, v.second) }, label = { Text(v.second) })
                    }
                }
            },
        ) { pad ->
            Box(Modifier.padding(pad)) {
                when (screen) {
                    "chat" -> ChatScreen(container, onSettings = { screen = "settings" })
                    "actions" -> ActionCenterScreen(container)
                    "memory" -> MemoryScreen(container)
                    "library" -> LibraryScreen(container)
                    else -> SettingsScreen(container, onBack = { screen = "chat" })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(container: AppContainer, onSettings: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lines = remember { mutableStateListOf<ChatLine>() }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var voiceOn by remember { mutableStateOf(false) }
    val voiceState by container.voice.state.collectAsState()
    val partial by container.voice.partialTranscript.collectAsState()
    val voiceError by container.voice.lastError.collectAsState()
    val listState = rememberLazyListState()

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { VoiceSessionService.start(context); voiceOn = true }
    }

    fun send(text: String) {
        if (text.isBlank() || busy) return
        busy = true
        lines += ChatLine("Vous", text)
        val idx = lines.size
        lines += ChatLine("Forge", "")
        scope.launch {
            val buf = StringBuilder()
            container.orchestrator.handle(text, container.conversation, voice = false).collect { e ->
                when (e) {
                    is OrchestratorEvent.ResearchDone -> lines[idx] = lines[idx].copy(meta = (lines[idx].meta ?: "") + " · ${e.sourceCount} source(s) consultée(s)")
                    is OrchestratorEvent.ActionResult -> if (e.outcome.status == ai.forge.reasoning.ActionOutcome.Status.NEEDS_CONFIRMATION)
                        lines[idx] = lines[idx].copy(pendingAction = e.call)
                    is OrchestratorEvent.SecurityWarning -> lines += ChatLine("Sécurité", "Contenu externe suspect ignoré comme instruction (${e.source}) : ${e.findings.joinToString()}")
                    is OrchestratorEvent.Planned -> lines[idx] = lines[idx].copy(meta = "${e.intent.primary.name.lowercase()} · ${e.intent.depth.label} · ${e.provider}")
                    is OrchestratorEvent.Token -> { buf.append(e.text); lines[idx] = lines[idx].copy(text = buf.toString()) }
                    is OrchestratorEvent.QualityReview -> lines[idx] = lines[idx].copy(meta = (lines[idx].meta ?: "") + " · contrôle : ${e.verdict}")
                    is OrchestratorEvent.MemoryEvent -> lines += ChatLine("Mémoire", e.outcome.toString().take(160))
                    is OrchestratorEvent.Failed -> { lines[idx] = lines[idx].copy(text = "⚠️ ${e.reason}"); container.voice.signal(PresenceSignal.Failure(e.reason)) }
                    else -> Unit
                }
            }
            busy = false
            listState.animateScrollToItem(lines.lastIndex.coerceAtLeast(0))
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("FORGE") },
                actions = {
                    IconButton(onClick = { container.newConversation(); lines.clear() }) { Icon(Icons.Default.AddComment, "Nouvelle conversation") }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Réglages") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            VoiceIndicator(container, voiceOn, partial, voiceError, container.settings.showTranscript)
            LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), state = listState) {
                items(lines.size) { i ->
                    val l = lines[i]
                    MessageBubble(l)
                    l.pendingAction?.let { call ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                lines[i] = l.copy(pendingAction = null)
                                scope.launch { lines += ChatLine("Forge", container.orchestrator.executeConfirmed(call, container.conversation)) }
                            }) { Text("Confirmer") }
                            OutlinedButton(onClick = { lines[i] = l.copy(pendingAction = null); lines += ChatLine("Forge", "Action annulée, rien n'a été exécuté.") }) { Text("Annuler") }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("Parlez à Forge…") }, maxLines = 5)
                IconButton(onClick = { send(input); input = "" }, enabled = !busy) { Icon(Icons.AutoMirrored.Filled.Send, "Envoyer") }
                if (voiceOn && voiceState == VoiceState.SPEAKING) {
                    IconButton(onClick = { container.voice.interrupt() }) { Icon(Icons.Default.PanTool, "Interrompre") }
                }
                FilledIconButton(onClick = {
                    if (voiceOn) { VoiceSessionService.stop(context); voiceOn = false }
                    else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        VoiceSessionService.start(context); voiceOn = true
                    } else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }) { Icon(if (voiceOn) Icons.Default.MicOff else Icons.Default.Mic, if (voiceOn) "Arrêt d'urgence du micro" else "Parler") }
            }
        }
    }
}

@Composable
private fun VoiceIndicator(container: AppContainer, voiceOn: Boolean, partial: String, error: String?, showTranscript: Boolean) {
    val presence by container.voice.presence.collectAsState()
    val level by container.voice.audioLevel.collectAsState()
    val online by container.networkAvailable.collectAsState()
    val state = if (voiceOn) presence else PresenceState.STANDBY
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        ForgeOrb(state, level, size = 48.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(if (voiceOn) state.label else "Micro coupé", color = ForgePalette.Gold, style = MaterialTheme.typography.labelLarge)
            if (showTranscript && partial.isNotBlank()) Text(partial, style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = ForgePalette.Error, style = MaterialTheme.typography.bodySmall) }
            if (!online) Text("Hors-ligne : seules les fonctions locales sont disponibles.", color = ForgePalette.TextMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun MessageBubble(l: ChatLine) {
    val mine = l.author == "Vous"
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Text(l.author + (l.meta?.let { " · $it" } ?: ""), style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        Text(l.text, Modifier.clip(RoundedCornerShape(12.dp))
            .background(if (mine) Color(0xFF2A2F38) else MaterialTheme.colorScheme.surface).padding(10.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit) {
    var s by remember { mutableStateOf(container.settings) }
    var anthropicKey by remember { mutableStateOf("") }
    var oaiKey by remember { mutableStateOf("") }
    var testResult by remember { mutableStateOf<String?>(null) }
    var braveKey by remember { mutableStateOf("") }
    var modelStatus by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    // Modèle local : l'utilisateur choisit le fichier .task (ex. Gemma) ; copié dans l'espace privé de l'app.
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) container.scope.launch {
            modelStatus = "Copie du modèle en cours…"
            modelStatus = runCatching {
                val dir = java.io.File(context.filesDir, "models").apply { mkdirs() }
                val target = java.io.File(dir, "local-model.task")
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)!!.use { i -> target.outputStream().use { o -> i.copyTo(o, 1 shl 20) } }
                }
                s = s.copy(localModelPath = target.absolutePath)
                "Modèle prêt (${target.length() / 1_000_000} Mo). Enregistrez les réglages."
            }.getOrElse { "Échec de la copie : ${it.message}" }
        }
    }
    val scope = rememberCoroutineScope()
    Scaffold(topBar = {
        TopAppBar(title = { Text("Réglages") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") }
        })
    }) { pad ->
        LazyColumn(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text("Moteurs d'intelligence", style = MaterialTheme.typography.titleMedium) }
            item {
                Column {
                    Text(s.localModelPath?.let { "Modèle local : ${java.io.File(it).name}" } ?: "Aucun modèle local", color = ForgePalette.TextMuted)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { modelPicker.launch(arrayOf("*/*")) }) { Text("Choisir un modèle local (.task)") }
                        if (s.localModelPath != null) OutlinedButton(onClick = { s = s.copy(localModelPath = null) }) { Text("Retirer") }
                    }
                    modelStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ForgePalette.Gold) }
                }
            }
            item { OutlinedTextField(s.lanServerUrl.orEmpty(), { s = s.copy(lanServerUrl = it.ifBlank { null }) },
                label = { Text("Serveur réseau local (Ollama / llama.cpp), ex. http://192.168.1.10:11434/v1") }, modifier = Modifier.fillMaxWidth()) }
            item { OutlinedTextField(s.lanModel.orEmpty(), { s = s.copy(lanModel = it.ifBlank { null }) },
                label = { Text("Modèle du serveur local") }, modifier = Modifier.fillMaxWidth()) }
            item { OutlinedTextField(s.openAiCompatibleUrl.orEmpty(), { s = s.copy(openAiCompatibleUrl = it.ifBlank { null }) },
                label = { Text("API compatible OpenAI (facultatif) — URL") }, modifier = Modifier.fillMaxWidth()) }
            item { OutlinedTextField(s.openAiCompatibleModel.orEmpty(), { s = s.copy(openAiCompatibleModel = it.ifBlank { null }) },
                label = { Text("Modèle") }, modifier = Modifier.fillMaxWidth()) }
            item { OutlinedTextField(oaiKey, { oaiKey = it }, label = { Text("Clé API compatible (laisser vide = inchangée)") }, modifier = Modifier.fillMaxWidth()) }
            item { OutlinedTextField(anthropicKey, { anthropicKey = it }, label = { Text("Clé API Anthropic (facultatif)") }, modifier = Modifier.fillMaxWidth()) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(s.localOnly, { s = s.copy(localOnly = it) }); Spacer(Modifier.width(8.dp))
                    Text("Mode strictement local (aucun appel cloud)")
                }
            }
            item {
                // §14 : tester réellement chaque moteur configuré (appel de disponibilité, aucune donnée privée envoyée).
                OutlinedButton(onClick = {
                    scope.launch {
                        val results = mutableListOf<String>()
                        for (p in container.providers()) {
                            results += "${p.displayName} : " + p.testConnection().fold({ it }, { "échec — ${it.message}" })
                        }
                        testResult = results.joinToString("\n").ifEmpty { "Aucun moteur configuré." }
                    }
                }) { Text("Tester les connexions") }
                testResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            item { Text("Recherche Web", style = MaterialTheme.typography.titleMedium) }
            item { OutlinedTextField(s.searxngUrl.orEmpty(), { s = s.copy(searxngUrl = it.ifBlank { null }) },
                label = { Text("SearXNG (recommandé, auto-hébergeable) — URL") }, modifier = Modifier.fillMaxWidth()) }
            item { OutlinedTextField(braveKey, { braveKey = it }, label = { Text("Clé Brave Search (facultatif, service tiers)") }, modifier = Modifier.fillMaxWidth()) }
            item { Text("La requête de recherche est envoyée au service choisi ; vos documents et souvenirs ne le sont jamais.",
                style = MaterialTheme.typography.bodySmall, color = ForgePalette.TextMuted) }
            item { Text("Fournisseurs externes (cloud)", style = MaterialTheme.typography.titleMedium) }
            item { Text("Données qu'ils peuvent recevoir. Les comics, Drive et cours ne partent jamais sans votre accord explicite ici.",
                style = MaterialTheme.typography.bodySmall, color = ForgePalette.TextMuted) }
            items(listOf(DataClass.GENERAL, DataClass.PERSONAL, DataClass.PRIVATE_DOCUMENTS)) { dc ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(s.cloudMaxDataClass == dc, { s = s.copy(cloudMaxDataClass = dc) })
                    Text(when (dc) { DataClass.GENERAL -> "Demande seule (recommandé)"; DataClass.PERSONAL -> "Demande + souvenirs personnels"
                        else -> "Demande + souvenirs + documents privés" })
                }
            }
            item { Text("Tâches autorisées pour le cloud", style = MaterialTheme.typography.labelLarge) }
            items(TaskCategory.entries.toList()) { t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(t in s.cloudTasks, { on -> s = s.copy(cloudTasks = if (on) s.cloudTasks + t else s.cloudTasks - t) })
                    Text(t.label)
                }
            }
            item { OutlinedTextField((s.cloudDailyRequestLimit ?: 0).toString(), { v -> s = s.copy(cloudDailyRequestLimit = v.toIntOrNull()?.takeIf { it > 0 }) },
                label = { Text("Limite de requêtes cloud par jour (0 = aucune)") }, modifier = Modifier.fillMaxWidth()) }
            item { Text("Usage : " + listOf("oai", "anthropic").joinToString(" · ") { container.usage.summary(it) },
                style = MaterialTheme.typography.bodySmall, color = ForgePalette.TextMuted) }
            item { Text("Plafond d'autonomie", style = MaterialTheme.typography.titleMedium) }
            items(AutonomyLevel.entries.toList()) { lvl ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(s.autonomyCeiling == lvl, { s = s.copy(autonomyCeiling = lvl) })
                    Text("${lvl.ordinal} — ${lvl.label}")
                }
            }
            item { Text("Les actions sensibles ou irréversibles demandent toujours votre confirmation, quel que soit ce plafond.",
                style = MaterialTheme.typography.bodySmall, color = Color.Gray) }
            item { Text("Voix", style = MaterialTheme.typography.titleMedium) }
            item {
                Text("Vitesse : ${"%.2f".format(s.speechRate)}")
                Slider(s.speechRate, { s = s.copy(speechRate = it) }, valueRange = 0.6f..1.6f)
            }
            item {
                Button(onClick = {
                    if (anthropicKey.isNotBlank()) container.keys.put("anthropic", anthropicKey)
                    if (oaiKey.isNotBlank()) container.keys.put("oai", oaiKey)
                    if (braveKey.isNotBlank()) container.keys.put("brave", braveKey)
                    container.voice.tts.speechRate = s.speechRate
                    container.updateSettings(s)
                    onBack()
                }, modifier = Modifier.fillMaxWidth()) { Text("Enregistrer") }
            }
        }
    }
}

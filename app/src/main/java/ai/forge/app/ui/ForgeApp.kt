package ai.forge.app.ui

import ai.forge.app.AppContainer
import ai.forge.app.voice.VoiceSessionService
import ai.forge.core.model.AutonomyLevel
import ai.forge.core.orchestrator.OrchestratorEvent
import ai.forge.core.voice.VoiceState
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

private val ForgeColors = darkColorScheme(
    primary = Color(0xFFE0A84A), secondary = Color(0xFF6FB3D2),
    background = Color(0xFF0E1116), surface = Color(0xFF161B22), onBackground = Color(0xFFE6E6E6),
)

data class ChatLine(val author: String, val text: String, val meta: String? = null)

@Composable
fun ForgeApp(container: AppContainer) {
    MaterialTheme(colorScheme = ForgeColors) {
        var screen by remember { mutableStateOf("chat") }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (screen) {
                "chat" -> ChatScreen(container, onSettings = { screen = "settings" })
                else -> SettingsScreen(container, onBack = { screen = "chat" })
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
                    is OrchestratorEvent.Planned -> lines[idx] = lines[idx].copy(meta = "${e.intent.primary.name.lowercase()} · ${e.intent.depth.label} · ${e.provider}")
                    is OrchestratorEvent.Token -> { buf.append(e.text); lines[idx] = lines[idx].copy(text = buf.toString()) }
                    is OrchestratorEvent.QualityReview -> lines[idx] = lines[idx].copy(meta = (lines[idx].meta ?: "") + " · contrôle : ${e.verdict}")
                    is OrchestratorEvent.MemoryEvent -> lines += ChatLine("Mémoire", e.outcome.toString().take(160))
                    is OrchestratorEvent.Failed -> lines[idx] = lines[idx].copy(text = "⚠️ ${e.reason}")
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
            VoiceIndicator(if (voiceOn) voiceState else VoiceState.IDLE, partial, voiceError)
            LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), state = listState) {
                items(lines) { l -> MessageBubble(l) }
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
private fun VoiceIndicator(state: VoiceState, partial: String, error: String?) {
    val (label, color) = when (state) {
        VoiceState.IDLE -> "Micro coupé" to Color.Gray
        VoiceState.LISTENING -> "J'écoute" to Color(0xFF4CAF50)
        VoiceState.THINKING -> "Je réfléchis" to Color(0xFFE0A84A)
        VoiceState.SPEAKING -> "Je parle — parlez pour m'interrompre" to Color(0xFF6FB3D2)
    }
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        0.85f, 1.15f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "scale")
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(14.dp).scale(if (state == VoiceState.IDLE) 1f else pulse).clip(CircleShape).background(color))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(label, color = color, style = MaterialTheme.typography.labelLarge)
            if (partial.isNotBlank()) Text(partial, style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = Color(0xFFE57373), style = MaterialTheme.typography.bodySmall) }
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
    Scaffold(topBar = {
        TopAppBar(title = { Text("Réglages") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") }
        })
    }) { pad ->
        LazyColumn(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text("Moteurs d'intelligence", style = MaterialTheme.typography.titleMedium) }
            item { OutlinedTextField(s.localModelPath.orEmpty(), { s = s.copy(localModelPath = it.ifBlank { null }) },
                label = { Text("Modèle local (.task) — chemin sur l'appareil") }, modifier = Modifier.fillMaxWidth()) }
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
                    container.voice.tts.speechRate = s.speechRate
                    container.updateSettings(s)
                    onBack()
                }, modifier = Modifier.fillMaxWidth()) { Text("Enregistrer") }
            }
        }
    }
}

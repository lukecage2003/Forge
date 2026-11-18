package ai.forge.app.ui

import ai.forge.app.AppContainer
import ai.forge.app.voice.VoiceSessionService
import ai.forge.reasoning.ActionOutcome
import ai.forge.reasoning.OrchestratorEvent
import ai.forge.voice.PresenceSignal
import ai.forge.voice.PresenceState
import ai.forge.voice.VoiceState
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import java.time.LocalTime

private val suggestions = listOf(
    "Explique-moi l'origine du vibranium",
    "Aide-moi à écrire un Short sur Black Panther",
    "Fais-moi réviser mon cours",
    "Mets un réveil à 7 h",
    "Raconte-moi une anecdote sur Superman",
)

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(container: AppContainer, onSettings: () -> Unit) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val lines = remember { mutableStateListOf<ChatLine>() }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var voiceOn by remember { mutableStateOf(false) }
    val voiceState by container.voice.state.collectAsState()
    val presence by container.voice.presence.collectAsState()
    val level by container.voice.audioLevel.collectAsState()
    val partial by container.voice.partialTranscript.collectAsState()
    val online by container.networkAvailable.collectAsState()
    val brain by container.brain.collectAsState()
    val brainDetail by container.brainDetail.collectAsState()
    val listState = rememberLazyListState()
    var pageToImport by remember { mutableStateOf<android.net.Uri?>(null) }
    val pagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> pageToImport = uri }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { VoiceSessionService.start(context); voiceOn = true }
    }
    fun toggleVoice() {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        if (voiceOn) { VoiceSessionService.stop(context); voiceOn = false }
        else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            VoiceSessionService.start(context); voiceOn = true
        } else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    fun send(text: String) {
        if (text.isBlank() || busy) return
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        busy = true
        lines += ChatLine("Vous", text.trim())
        val idx = lines.size
        lines += ChatLine("Forge", "")
        scope.launch {
            listState.animateScrollToItem(lines.lastIndex)
            val buf = StringBuilder()
            container.orchestrator.handle(text, container.conversation, voice = false).collect { e ->
                when (e) {
                    is OrchestratorEvent.ResearchDone -> lines[idx] = lines[idx].copy(meta = "${e.sourceCount} source(s) consultée(s)")
                    is OrchestratorEvent.LibraryResults -> lines[idx] = lines[idx].copy(meta = "${e.passages.size} passage(s) trouvé(s) dans vos comics")
                    is OrchestratorEvent.ActionResult -> if (e.outcome.status == ActionOutcome.Status.NEEDS_CONFIRMATION)
                        lines[idx] = lines[idx].copy(pendingAction = e.call)
                    is OrchestratorEvent.SecurityWarning -> lines += ChatLine("Sécurité", "Contenu externe suspect ignoré comme instruction (${e.source}).")
                    is OrchestratorEvent.Token -> { buf.append(e.text); lines[idx] = lines[idx].copy(text = buf.toString()) }
                    is OrchestratorEvent.MemoryEvent -> lines += ChatLine("Mémoire", memoryLabel(e))
                    is OrchestratorEvent.Failed -> { lines[idx] = lines[idx].copy(text = e.reason, meta = "Erreur"); container.voice.signal(PresenceSignal.Failure(e.reason)) }
                    else -> Unit
                }
            }
            busy = false
        }
    }

    // Défilement automatique pendant la génération.
    LaunchedEffect(lines.lastOrNull()?.text?.length) { if (lines.isNotEmpty() && busy) listState.scrollToItem(lines.lastIndex, Int.MAX_VALUE / 2) }

    val orbState = when { voiceOn -> presence; busy -> PresenceState.THINKING; brain is AppContainer.BrainState.Error -> PresenceState.ERROR; else -> PresenceState.STANDBY }

    pageToImport?.let { uri ->
        PageImportDialog(container, uri, onDone = { msg -> pageToImport = null; lines += ChatLine("Mémoire", msg) }, onCancel = { pageToImport = null })
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        // En-tête flottant.
        GlassSurface(Modifier.padding(horizontal = 14.dp, vertical = 6.dp).fillMaxWidth(), shape = RoundedCornerShape(26.dp),
            contentPadding = PaddingValues(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ForgeOrb(orbState, level, size = 34.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Forge", style = MaterialTheme.typography.titleMedium, color = ForgePalette.Text)
                    Text(statusLine(voiceOn, presence, brain, brainDetail, online), style = MaterialTheme.typography.labelMedium, color = ForgePalette.TextMuted, maxLines = 1)
                }
                IconButton(onClick = { container.newConversation(); lines.clear() }) { Icon(Icons.Default.Edit, "Nouvelle conversation", tint = ForgePalette.Text) }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (lines.isEmpty()) {
                Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    ForgeOrb(orbState, level, size = 168.dp)
                    Spacer(Modifier.height(18.dp))
                    Text(greeting(), style = MaterialTheme.typography.displayLarge, color = ForgePalette.Text)
                    Text("Je vous écoute. Posez-moi n'importe quelle question.", style = MaterialTheme.typography.bodyLarge, color = ForgePalette.TextMuted,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Spacer(Modifier.height(18.dp))
                    BrainCard(container, onOpenSettings = onSettings)
                    if (brain == AppContainer.BrainState.Ready) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        suggestions.forEach { s -> GlassPill(s) { send(s) } }
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    itemsIndexed(lines) { i, l ->
                        Column(Modifier.animateItem(fadeInSpec = tween(250))) {
                                Message(l, thinking = busy && i == lines.lastIndex && l.text.isEmpty())
                                l.pendingAction?.let { call ->
                                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        GoldButton("Confirmer") {
                                            lines[i] = l.copy(pendingAction = null)
                                            scope.launch { lines += ChatLine("Forge", container.orchestrator.executeConfirmed(call, container.conversation)) }
                                        }
                                        GlassPill("Annuler") { lines[i] = l.copy(pendingAction = null); lines += ChatLine("Forge", "Action annulée, rien n'a été exécuté.") }
                                    }
                                }
                            }
                        }
                    }
                }
            }

        // Transcription en direct (mode vocal).
        if (voiceOn && partial.isNotBlank() && container.settings.showTranscript)
            Text("« $partial »", Modifier.padding(horizontal = 24.dp, vertical = 4.dp), style = MaterialTheme.typography.bodyMedium, color = ForgePalette.GoldSoft)

        // Barre de saisie flottante.
        GlassSurface(Modifier.padding(start = 14.dp, end = 14.dp, bottom = TabBarSpace + 6.dp, top = 4.dp).navigationBarsPadding().fillMaxWidth(),
            shape = RoundedCornerShape(30.dp), contentPadding = PaddingValues(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AddPhotoAlternate, "Ajouter une page de comic comme preuve", tint = ForgePalette.GoldSoft,
                    modifier = Modifier.padding(end = 10.dp).size(24.dp).clickableNoRipple {
                        pagePicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    })
                Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
                    if (input.isEmpty()) Text(if (voiceOn) "Parlez, je vous écoute…" else "Demandez n'importe quoi…",
                        style = MaterialTheme.typography.bodyLarge, color = ForgePalette.TextMuted)
                    BasicTextField(input, { input = it }, textStyle = TextStyle(color = ForgePalette.Text, fontSize = 16.5.sp),
                        cursorBrush = SolidColor(ForgePalette.Gold), maxLines = 6, modifier = Modifier.fillMaxWidth())
                }
                if (voiceOn && voiceState == VoiceState.SPEAKING)
                    RoundIcon(Icons.Default.PanTool, "Interrompre", Color.White.copy(alpha = 0.12f), ForgePalette.Text) { container.voice.interrupt() }
                if (input.isBlank()) {
                    RoundIcon(if (voiceOn) Icons.Default.Stop else Icons.Default.Mic, if (voiceOn) "Arrêter le micro" else "Parler",
                        if (voiceOn) ForgePalette.Error.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.12f), ForgePalette.Text) { toggleVoice() }
                } else {
                    RoundIcon(Icons.AutoMirrored.Filled.Send, "Envoyer", ForgePalette.Gold, ForgePalette.Black, enabled = !busy) { send(input); input = "" }
                }
            }
        }
    }
}

@Composable
private fun RoundIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, bg: Color, fg: Color, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.padding(start = 6.dp).size(44.dp).clip(CircleShape).background(bg).clickable(enabled = enabled, onClick = onClick).alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.Center) { Icon(icon, label, tint = fg, modifier = Modifier.size(22.dp)) }
}

@Composable
private fun Message(l: ChatLine, thinking: Boolean) {
    when (l.author) {
        "Vous" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(l.text, Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp))
                .background(Brush.linearGradient(listOf(ForgePalette.GoldSoft, ForgePalette.Gold))).padding(horizontal = 16.dp, vertical = 11.dp),
                style = MaterialTheme.typography.bodyLarge, color = ForgePalette.Black)
        }
        "Forge" -> Column(Modifier.fillMaxWidth().padding(end = 24.dp)) {
            if (thinking) TypingDots() else Text(RichText.render(l.text), style = MaterialTheme.typography.bodyLarge, color = ForgePalette.Text)
            l.meta?.let { Text(it, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall,
                color = if (it == "Erreur") ForgePalette.Error else ForgePalette.TextMuted) }
        }
        else -> GlassSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), contentPadding = PaddingValues(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (l.author == "Mémoire") Icons.Default.Psychology else Icons.Default.Shield, null, tint = ForgePalette.GoldSoft, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(l.text, style = MaterialTheme.typography.bodySmall, color = ForgePalette.TextMuted)
            }
        }
    }
}

@Composable
private fun TypingDots() {
    val t = rememberInfiniteTransition(label = "dots")
    Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(3) { i ->
            val a by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(600, delayMillis = i * 150), RepeatMode.Reverse), label = "d$i")
            Box(Modifier.size(8.dp).alpha(a).clip(CircleShape).background(ForgePalette.Gold))
        }
    }
}

private fun memoryLabel(e: OrchestratorEvent.MemoryEvent): String = when (val o = e.outcome) {
    is ai.forge.memory.RememberOutcome.Stored -> "Mémorisé : ${o.item.text}"
    is ai.forge.memory.RememberOutcome.MergedIntoExisting -> "Déjà en mémoire : ${o.item.text}"
    is ai.forge.memory.RememberOutcome.Conflict -> "Contradiction avec un souvenir : ${o.existing.text}"
    is ai.forge.memory.RememberOutcome.PendingConfirmation -> "À confirmer dans l'onglet Mémoire : ${o.candidate.text}"
}

private fun greeting(): String = when (LocalTime.now().hour) { in 5..11 -> "Bonjour."; in 12..17 -> "Bon après-midi."; else -> "Bonsoir." }

private fun statusLine(voiceOn: Boolean, presence: PresenceState, brain: AppContainer.BrainState, detail: String?, online: Boolean): String = when {
    voiceOn -> presence.label
    brain is AppContainer.BrainState.Downloading -> "Installation du cerveau…"
    brain is AppContainer.BrainState.Missing -> "Cerveau non installé"
    detail != null -> detail + if (!online) " · hors-ligne" else ""
    !online -> "Hors-ligne · fonctions locales"
    else -> "Prêt"
}

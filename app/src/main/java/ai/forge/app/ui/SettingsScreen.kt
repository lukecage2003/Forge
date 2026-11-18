package ai.forge.app.ui

import ai.forge.app.AppContainer
import ai.forge.core.model.AutonomyLevel
import ai.forge.core.model.DataClass
import ai.forge.core.model.TaskCategory
import ai.forge.models.DownloadableModel
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(title.uppercase(), Modifier.padding(start = 8.dp, bottom = 6.dp), style = MaterialTheme.typography.labelMedium, color = ForgePalette.TextMuted)
        GlassSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), contentPadding = PaddingValues(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        }
    }
}

@Composable
private fun Field(value: String, label: String, onChange: (String) -> Unit) = OutlinedTextField(value, onChange, label = { Text(label) },
    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), singleLine = true,
    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = ForgePalette.Gold, unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedLabelColor = ForgePalette.Gold))

@Composable
private fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = ForgePalette.TextMuted)

@Composable
fun SettingsScreen(container: AppContainer) {
    var s by remember { mutableStateOf(container.settings) }
    var anthropicKey by remember { mutableStateOf("") }
    var oaiKey by remember { mutableStateOf("") }
    var braveKey by remember { mutableStateOf("") }
    var youtubeKey by remember { mutableStateOf("") }
    var testResult by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }
    var removeNeural by remember { mutableStateOf(false) }
    val brain by container.brain.collectAsState()
    val detail by container.brainDetail.collectAsState()
    var choice by remember { mutableStateOf(DownloadableModel.RECOMMENDED) }
    val scope = rememberCoroutineScope()
    val switchColors = SwitchDefaults.colors(checkedTrackColor = ForgePalette.Gold, checkedThumbColor = ForgePalette.Black)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = TabBarSpace + 40.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)) {
        item { LargeTitle("Réglages", "Votre Forge, vos règles.") }

        item {
            Section("Cerveau") {
                val current = s.localModelPath?.let { java.io.File(it).name }
                Text(current?.let { DownloadableModel.byFileName(it)?.displayName ?: it } ?: "Aucun modèle sur le téléphone",
                    style = MaterialTheme.typography.titleMedium, color = ForgePalette.Text)
                detail?.let { Hint(it) }
                Hint("Changer de modèle : choisissez-en un et installez-le. L'ancien fichier peut ensuite être supprimé.")
                ModelChoices(choice) { choice = it }
                when (val b = brain) {
                    is AppContainer.BrainState.Downloading -> { GlassProgress(b.downloaded.toFloat() / b.total); Hint("${b.downloaded / 1_000_000} / ${b.total / 1_000_000} Mo") }
                    else -> {
                        val installed = current == choice.fileName
                        GoldButton(if (installed) "Modèle actif" else "Installer ${choice.displayName}", Modifier.fillMaxWidth(), enabled = !installed) { container.installBrain(choice) }
                        (b as? AppContainer.BrainState.Error)?.let { Text(it.message, color = ForgePalette.Error, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                if (current != null) GlassPill("Libérer l'espace des anciens modèles") {
                    val dir = java.io.File(container.app.filesDir, "models")
                    dir.listFiles()?.filter { it.absolutePath != s.localModelPath }?.forEach { it.delete() }
                    testResult = "Anciens modèles supprimés."
                }
            }
        }

        item {
            Section("Autres moteurs (facultatif)") {
                Field(s.lanServerUrl.orEmpty(), "PC à la maison (Ollama) — ex. http://192.168.1.10:11434/v1") { s = s.copy(lanServerUrl = it.ifBlank { null }) }
                Field(s.lanModel.orEmpty(), "Modèle sur le PC (ex. qwen3:8b)") { s = s.copy(lanModel = it.ifBlank { null }) }
                Field(s.openAiCompatibleUrl.orEmpty(), "API compatible OpenAI — URL") { s = s.copy(openAiCompatibleUrl = it.ifBlank { null }) }
                Field(s.openAiCompatibleModel.orEmpty(), "Modèle de l'API") { s = s.copy(openAiCompatibleModel = it.ifBlank { null }) }
                Field(oaiKey, "Clé de l'API (vide = inchangée)") { oaiKey = it }
                Field(anthropicKey, "Clé Anthropic (facultatif)") { anthropicKey = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Strictement local", color = ForgePalette.Text); Hint("Aucun appel à un service en ligne.") }
                    Switch(s.localOnly, { s = s.copy(localOnly = it) }, colors = switchColors)
                }
                GlassPill("Tester les connexions") {
                    scope.launch {
                        val results = mutableListOf<String>()
                        for (p in container.providers()) results += "${p.displayName} : " + p.testConnection().fold({ it }, { "échec — ${it.message}" })
                        testResult = results.joinToString("\n").ifEmpty { "Aucun moteur configuré." }
                    }
                }
                testResult?.let { Hint(it) }
            }
        }

        item {
            Section("Recherche Web") {
                Field(s.searxngUrl.orEmpty(), "SearXNG — URL") { s = s.copy(searxngUrl = it.ifBlank { null }) }
                Field(braveKey, "Clé Brave Search (facultatif)") { braveKey = it }
                Hint("Seule la question est envoyée au service de recherche ; vos documents et souvenirs ne le sont jamais.")
            }
        }

        item {
            Section("Analyse YouTube") {
                Field(youtubeKey, if (container.keys.get("youtube") != null) "Clé YouTube Data API (enregistrée)" else "Clé YouTube Data API (gratuite)") { youtubeKey = it }
                Hint("Console Google Cloud → activer « YouTube Data API v3 » → Identifiants → Clé API. Forge analyse les vidéos qui surperforment sur votre sujet pour améliorer vos scripts.")
            }
        }

        item {
            Section("Confidentialité du cloud") {
                Hint("Ce qu'un service en ligne peut recevoir. Vos comics et documents ne partent jamais sans votre accord ici.")
                listOf(DataClass.GENERAL to "La question seule (recommandé)", DataClass.PERSONAL to "Question + souvenirs personnels",
                    DataClass.PRIVATE_DOCUMENTS to "Question + souvenirs + documents").forEach { (dc, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(s.cloudMaxDataClass == dc, { s = s.copy(cloudMaxDataClass = dc) }, colors = RadioButtonDefaults.colors(selectedColor = ForgePalette.Gold))
                        Text(label, color = ForgePalette.Text)
                    }
                }
                Text("Tâches autorisées", style = MaterialTheme.typography.labelLarge, color = ForgePalette.Text)
                TaskCategory.entries.forEach { t ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t.label, Modifier.weight(1f), color = ForgePalette.Text)
                        Switch(t in s.cloudTasks, { on -> s = s.copy(cloudTasks = if (on) s.cloudTasks + t else s.cloudTasks - t) }, colors = switchColors)
                    }
                }
                Field((s.cloudDailyRequestLimit ?: 0).toString(), "Requêtes cloud par jour (0 = illimité)") { v -> s = s.copy(cloudDailyRequestLimit = v.toIntOrNull()?.takeIf { it > 0 }) }
                Hint("Usage : " + listOf("oai", "anthropic").joinToString(" · ") { container.usage.summary(it) })
            }
        }

        item {
            Section("Autonomie") {
                AutonomyLevel.entries.forEach { lvl ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(s.autonomyCeiling == lvl, { s = s.copy(autonomyCeiling = lvl) }, colors = RadioButtonDefaults.colors(selectedColor = ForgePalette.Gold))
                        Text("${lvl.ordinal} · ${lvl.label}", color = ForgePalette.Text)
                    }
                }
                Hint("Les actions sensibles ou irréversibles demandent toujours votre confirmation.")
            }
        }

        item {
            Section("Bulle et veille « Forge »") {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                val wakeState by container.wakeInstall.collectAsState()
                var overlayOk by remember { mutableStateOf(android.provider.Settings.canDrawOverlays(ctx)) }
                var wakeReady by remember { mutableStateOf(container.wakeModelDir() != null) }
                val mic = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
                    if (ok) { s = s.copy(wakeStandby = true); container.updateSettings(container.settings.copy(wakeStandby = true)); ai.forge.app.voice.VoiceSessionService.standby(ctx) }
                }
                LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1_500); overlayOk = android.provider.Settings.canDrawOverlays(ctx) } }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Bulle animée", color = ForgePalette.Text)
                        Hint("En haut de l'écran, par-dessus vos applications : votre voix en onde dorée, puis la réponse de Forge.") }
                    Switch(s.islandEnabled, { s = s.copy(islandEnabled = it) }, colors = switchColors)
                }
                if (s.islandEnabled && !overlayOk) GlassPill("Autoriser l'affichage par-dessus les applications") {
                    ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + ctx.packageName))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }

                Text("Dites « Forge »", style = MaterialTheme.typography.titleMedium, color = ForgePalette.Text)
                Hint("Détecteur hors-ligne (Vosk, 42 Mo) : il n'écoute que le mot « Forge », rien ne quitte le téléphone. Fonctionne écran allumé, dans n'importe quelle application ; une notification indique que le micro est actif.")
                if (!wakeReady) GlassPill("Installer le mot d'appel") { container.installWakeWord { wakeReady = true } }
                else Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Veille : répondre quand je dis « Forge »", Modifier.weight(1f), color = ForgePalette.Text)
                    Switch(s.wakeStandby, { on ->
                        if (on) mic.launch(android.Manifest.permission.RECORD_AUDIO)
                        else { s = s.copy(wakeStandby = false); container.updateSettings(container.settings.copy(wakeStandby = false)); ai.forge.app.voice.VoiceSessionService.stop(ctx) }
                    }, colors = switchColors)
                }
                wakeState?.let { Hint(it) }
                if (wakeReady) {
                    Text("Sensibilité : ${if (s.wakeStrictness < 0.5) "élevée" else if (s.wakeStrictness < 0.65) "normale" else "stricte"}", color = ForgePalette.Text)
                    Slider((0.8 - s.wakeStrictness).toFloat() / 0.4f, { v -> s = s.copy(wakeStrictness = 0.8 - v * 0.4) },
                        colors = SliderDefaults.colors(thumbColor = ForgePalette.Gold, activeTrackColor = ForgePalette.Gold))
                    Hint("Trop de faux réveils : réduisez. Forge ne vous entend pas : augmentez.")
                    GlassPill("Empêcher Samsung d'endormir Forge") {
                        runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:" + ctx.packageName))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                }
            }
        }

        item {
            Section("Briefing du matin") {
                val notifPerm = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Briefing chaque matin (7 h 30)", color = ForgePalette.Text)
                        Hint("Projets en cours, fiches à réviser, script en préparation. Calculé sur le téléphone.") }
                    Switch(s.morningBriefing, { on ->
                        s = s.copy(morningBriefing = on)
                        if (on && android.os.Build.VERSION.SDK_INT >= 33) notifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }, colors = switchColors)
                }
            }
        }

        item {
            Section("Assistant du téléphone") {
                Hint("Faites de Forge l'assistant numérique : un appui long sur le bouton latéral l'ouvre directement en écoute, même écran verrouillé.")
                Hint("1. Touchez le bouton ci-dessous → Assistant numérique → Forge.  2. Paramètres → Fonctions avancées → Touche latérale → Appui prolongé → Assistant numérique.")
                val ctx = androidx.compose.ui.platform.LocalContext.current
                GlassPill("Choisir Forge comme assistant") {
                    runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
        }

        item {
            Section("Voix") {
                val neuralState by container.neuralVoiceInstall.collectAsState()
                Text("Voix neuronale hors-ligne", style = MaterialTheme.typography.titleMedium, color = ForgePalette.Text)
                Hint("« Estelle » (Kyutai Pocket TTS, licence CC-BY 4.0) : voix naturelle, sur le téléphone, même sans Internet. 127 Mo.")
                if (s.neuralVoicePath == null && neuralState?.startsWith("Voix Estelle installée") != true) GlassPill("Installer la voix Estelle") { container.installNeuralVoice() }
                else Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Voix Estelle active", Modifier.weight(1f), color = ForgePalette.GoldSoft)
                    TextButton(onClick = { s = s.copy(neuralVoicePath = null); removeNeural = true }) { Text("Utiliser la voix du téléphone", color = ForgePalette.TextMuted) }
                }
                neuralState?.let { Hint(it) }
                VoicePicker(container, s.voiceName, s.networkVoices, onPick = { s = s.copy(voiceName = it) }, onNetwork = { s = s.copy(networkVoices = it) })
                Text("Vitesse de parole : ${"%.2f".format(s.speechRate)}", color = ForgePalette.Text)
                Slider(s.speechRate, { s = s.copy(speechRate = it) }, valueRange = 0.9f..2.0f,
                    colors = SliderDefaults.colors(thumbColor = ForgePalette.Gold, activeTrackColor = ForgePalette.Gold))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Afficher la transcription", Modifier.weight(1f), color = ForgePalette.Text)
                    Switch(s.showTranscript, { s = s.copy(showTranscript = it) }, colors = switchColors)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Répondre seulement à « Forge »", color = ForgePalette.Text); Hint("Mains libres : dites « Forge, … » pour lui parler.") }
                    Switch(s.requireWakeWord, { s = s.copy(requireWakeWord = it) }, colors = switchColors)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("L'interrompre à la voix sans écouteurs", color = ForgePalette.Text)
                        Hint("Automatique avec écouteurs. Sans écouteurs, le micro peut capter la voix de Forge.") }
                    Switch(s.bargeInWithoutHeadset, { s = s.copy(bargeInWithoutHeadset = it) }, colors = switchColors)
                }
            }
        }

        item {
            GoldButton(if (saved) "Enregistré ✓" else "Enregistrer", Modifier.fillMaxWidth()) {
                if (anthropicKey.isNotBlank()) container.keys.put("anthropic", anthropicKey)
                if (oaiKey.isNotBlank()) container.keys.put("oai", oaiKey)
                if (braveKey.isNotBlank()) container.keys.put("brave", braveKey)
                if (youtubeKey.isNotBlank()) container.keys.put("youtube", youtubeKey)
                container.voice.tts.speechRate = s.speechRate
                // Les installations faites pendant que l'écran est ouvert (cerveau, voix) ne doivent pas être écrasées.
                val latest = container.settings
                container.updateSettings(s.copy(localModelPath = latest.localModelPath,
                    neuralVoicePath = if (removeNeural) null else latest.neuralVoicePath, wakeStandby = latest.wakeStandby))
                saved = true
            }
        }
    }
}

@Composable
private fun VoicePicker(container: AppContainer, selected: String?, network: Boolean, onPick: (String?) -> Unit, onNetwork: (Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    var voices by remember { mutableStateOf<List<android.speech.tts.Voice>>(emptyList()) }
    val active by container.voice.tts.activeVoice.collectAsState()
    LaunchedEffect(Unit) { voices = container.voice.tts.frenchVoices() }
    active?.let { Hint("Voix actuelle : $it") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text("Voix naturelles en ligne", color = ForgePalette.Text); Hint("Bien plus naturelles ; voix locale automatique hors-ligne.") }
        Switch(network, onNetwork, colors = SwitchDefaults.colors(checkedTrackColor = ForgePalette.Gold, checkedThumbColor = ForgePalette.Black))
    }
    if (voices.isEmpty()) Hint("Aucune voix française trouvée : installez « Synthèse vocale Google » et la voix française dans les paramètres du téléphone.")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected == null, { onPick(null) }, colors = RadioButtonDefaults.colors(selectedColor = ForgePalette.Gold))
            Text("Automatique (la plus naturelle disponible)", color = ForgePalette.Text)
        }
        voices.take(8).forEach { v ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected == v.name, { onPick(v.name) }, colors = RadioButtonDefaults.colors(selectedColor = ForgePalette.Gold))
                Text(container.voice.tts.describe(v), Modifier.weight(1f), color = ForgePalette.Text, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { scope.launch { container.voice.tts.preview(v.name); container.applyVoice() } }) { Text("Écouter", color = ForgePalette.GoldSoft) }
            }
        }
    }
}

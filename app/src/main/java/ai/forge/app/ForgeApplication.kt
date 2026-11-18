package ai.forge.app

import ai.forge.app.actions.CalendarDraftTool
import ai.forge.app.actions.OpenAppTool
import ai.forge.app.actions.SetAlarmTool
import ai.forge.app.actions.ShareTextTool
import ai.forge.app.llm.MediaPipeLocalProvider
import ai.forge.app.llm.LiteRtLmProvider
import ai.forge.models.LocalRuntime
import ai.forge.app.memory.ForgeDatabase
import ai.forge.app.memory.RoomMemoryStore
import ai.forge.app.settings.ForgeSettings
import ai.forge.app.settings.SecureKeyStore
import ai.forge.app.voice.VoiceCoordinator
import ai.forge.models.AnthropicProvider
import ai.forge.models.LlmProvider
import ai.forge.models.Locality
import ai.forge.models.OpenAiCompatibleProvider
import ai.forge.models.ProviderCapabilities
import ai.forge.models.ProviderRouter
import ai.forge.models.RoutingPolicy
import ai.forge.memory.HashingEmbedder
import ai.forge.memory.MemoryManager
import ai.forge.reasoning.ConversationContext
import ai.forge.reasoning.Orchestrator
import ai.forge.reasoning.ActionOutcome
import ai.forge.reasoning.ActionPlanner
import ai.forge.reasoning.ToolCallExecutor
import ai.forge.reasoning.ResearchContext
import ai.forge.reasoning.ResearchTool
import ai.forge.app.library.LibraryRepository
import ai.forge.app.library.MlKitOcrEngine
import ai.forge.browser.BraveSearchProvider
import ai.forge.browser.PageFetcher
import ai.forge.browser.ResearchSession
import ai.forge.browser.SearchProvider
import ai.forge.browser.SearxngSearchProvider
import ai.forge.actions.ActionStatus
import ai.forge.actions.ActionEngine
import ai.forge.models.UsageLimits
import ai.forge.models.UsageMeter
import ai.forge.models.DownloadableModel
import ai.forge.models.ModelDownloader
import kotlinx.coroutines.launch
import ai.forge.security.EgressPolicy
import ai.forge.security.ExternalProviderGrant
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import ai.forge.security.PermissionGate
import ai.forge.security.ToolRegistry
import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import java.util.UUID

class ForgeApplication : Application(), androidx.work.Configuration.Provider {
    /** Initialisation de WorkManager à la demande : fiable quel que soit l'ordre de démarrage. */
    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder().setMinimumLoggingLevel(android.util.Log.WARN).build()

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Le modèle reste chargé en arrière-plan (sinon chaque question après un passage par une autre
        // application rechargeait plusieurs Go : plusieurs secondes perdues). Il n'est libéré que si
        // Android est à court de mémoire et qu'aucune conversation vocale n'est en cours.
        @Suppress("DEPRECATION")
        if (level >= TRIM_MEMORY_COMPLETE && container.voice.state.value == ai.forge.voice.VoiceState.IDLE) container.onLowMemory()
    }
}

/** Assemblage des dépendances (injection manuelle : simple, explicite, sans magie). */
class AppContainer(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val keys = SecureKeyStore(app)
    var settings: ForgeSettings = ForgeSettings.load(app)
        private set

    private val db = ForgeDatabase.open(app)
    // Embedder lexical de repli ; à remplacer par un embedder sémantique local (voir ROADMAP).
    val memory = MemoryManager(RoomMemoryStore(db.memory()), HashingEmbedder())
    /** Historique des conversations et stockage des scripts/cours. */
    val history = ai.forge.app.history.ConversationStore(db.conversations())
    val kv = db.kv()
    val scripts = ai.forge.app.studio.ScriptRepository(kv)

    val gate = PermissionGate(settings.autonomyCeiling)
    val tools = ToolRegistry(listOf(SetAlarmTool(app), CalendarDraftTool(app), ShareTextTool(app), OpenAppTool(app)))
    /** Action Center : journal + arrêt d'urgence (§11). */
    val actions = ActionEngine(tools, gate)
    val usage = UsageMeter()
    val networkAvailable = MutableStateFlow(true)
    /** Ouverture par le bouton latéral / l'assistant : l'écran de conversation démarre l'écoute. */
    val voiceRequested = MutableStateFlow(false)
    /** Texte proposé dans la barre de saisie depuis un autre écran (Studio, Études…). */
    val prefill = MutableStateFlow<String?>(null)
    /** Jeton Google Drive de la session (jamais enregistré sur le disque). */
    @Volatile var driveToken: String? = null

    /** Arrêt d'urgence global : voix ET actions. */
    fun emergencyStop() { voice.stop(); actions.engageKillSwitch() }

    /** Pont entre l'orchestrateur et l'Action Engine : statuts réels, confirmation explicite. */
    /** Bibliothèque personnelle (comics, documents) indexée sur l'appareil. */
    /** OCR partagé (bibliothèque, images jointes, cours). */
    val ocr = MlKitOcrEngine()
    val library = LibraryRepository(java.io.File(app.filesDir, "library"), ocr)

    val academy = ai.forge.app.academy.AcademyRepository(kv, ocr, library)

    private val actionExecutor = ToolCallExecutor { call, confirmed ->
        if (confirmed) actions.confirm(call)
        val e = actions.run(call)
        val status = when (e.status) {
            ActionStatus.SUCCEEDED -> ActionOutcome.Status.DONE
            ActionStatus.AWAITING_CONFIRMATION -> ActionOutcome.Status.NEEDS_CONFIRMATION
            ActionStatus.DENIED -> ActionOutcome.Status.DENIED
            ActionStatus.BLOCKED_BY_KILL_SWITCH -> ActionOutcome.Status.BLOCKED
            else -> ActionOutcome.Status.FAILED
        }
        ActionOutcome(status, e.outcome)
    }

    // Un seul moteur local résident : réutilisé entre reconstructions, fermé quand le modèle change.
    private var localProvider: LlmProvider? = null
    private var localProviderPath: String? = null

    var conversation = ConversationContext(UUID.randomUUID().toString())
        private set

    // Déclarés avant l'orchestrateur : ils servent pendant sa construction.
    private val pageFetcher by lazy { PageFetcher() }
    /** Session gardée d'une reconstruction à l'autre : son cache (20 min) et robots.txt restent chauds. */
    private var researchSession: Pair<String, ResearchSession>? = null

    var orchestrator: Orchestrator = buildOrchestrator()
        private set

    val voice = VoiceCoordinator(app, scope, { orchestrator }, { conversation }).also {
        it.requireWakeWord = settings.requireWakeWord; it.bargeInEnabled = settings.bargeInWithoutHeadset
    }


    @Synchronized
    private fun localModel(): LlmProvider? {
        val path = settings.localModelPath
        if (path != localProviderPath) {
            releaseLocal()
            localProvider = path?.let { p ->
                val name = java.io.File(p).name
                val known = DownloadableModel.byFileName(name)
                when (LocalRuntime.forFile(name)) {
                    LocalRuntime.LITERT_LM -> LiteRtLmProvider(p, app.cacheDir.absolutePath, quality = known?.quality ?: 3,
                        displayName = "${known?.displayName ?: "Modèle local"} (sur le téléphone)")
                    LocalRuntime.MEDIAPIPE -> MediaPipeLocalProvider(app, p)
                }
            }
            localProviderPath = path
        }
        return localProvider
    }

    private fun releaseLocal() = when (val p = localProvider) {
        is LiteRtLmProvider -> p.release()
        is MediaPipeLocalProvider -> p.release()
        else -> Unit
    }

    /** Pression mémoire signalée par Android : on libère le modèle local (rechargé à la demande). */
    fun onLowMemory() { releaseLocal() }

    /** Charge le modèle en arrière-plan pour que la première réponse soit immédiate. */
    fun warmUpLocalModel() {
        val p = localModel() as? LiteRtLmProvider ?: return
        if (p.isLoaded) return
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            brainDetail.value = "Préparation du modèle…"
            brainDetail.value = p.warmUp().fold({ "Prêt · $it" }, { "Échec du chargement : ${it.message}" })
        }
    }
    /** Ligne d'état affichable : moteur prêt, GPU/CPU, erreur. */
    val brainDetail = MutableStateFlow<String?>(null)

    fun providers(): List<LlmProvider> = buildList {
        localModel()?.let { add(it) }
        if (!settings.lanServerUrl.isNullOrBlank() && !settings.lanModel.isNullOrBlank()) {
            add(OpenAiCompatibleProvider("lan", "Serveur local (${settings.lanModel})", settings.lanServerUrl!!, settings.lanModel!!,
                capabilities = ProviderCapabilities(Locality.LOCAL_NETWORK, 32_000, reasoningQuality = 3)))
        }
        if (!settings.openAiCompatibleUrl.isNullOrBlank() && !settings.openAiCompatibleModel.isNullOrBlank()) {
            add(OpenAiCompatibleProvider("oai", "API compatible (${settings.openAiCompatibleModel})",
                settings.openAiCompatibleUrl!!, settings.openAiCompatibleModel!!, keys.get("oai"),
                capabilities = ProviderCapabilities(Locality.CLOUD, 128_000, reasoningQuality = 4, relativeCost = 2)))
        }
        keys.get("anthropic")?.let { add(AnthropicProvider(it, settings.anthropicModel)) }
    }

    private fun egress() = EgressPolicy(listOf("oai", "anthropic").map {
        ExternalProviderGrant(it, settings.cloudTasks, settings.cloudMaxDataClass)
    })

    /** Vrai pendant un tour lancé par « Forge » (écran de réveil affiché tant qu'il dure). */
    val wakeTurnActive = MutableStateFlow(false)
    /** Fermeture demandée depuis l'écran de réveil. */
    @Volatile var onEndWakeTurn: () -> Unit = {}
    fun endWakeTurn() = onEndWakeTurn()

    /** Mesures réelles des derniers tours (diagnostic de vitesse). */
    val turnMetrics = MutableStateFlow<List<ai.forge.reasoning.TurnMetrics>>(emptyList())

    /** Test de vitesse du cerveau local : premier mot et vitesse d'écriture, mesurés sur ce téléphone. */
    suspend fun probeLocalModel(): String {
        val p = localModel() ?: return "Aucun cerveau installé sur le téléphone."
        if (p is LiteRtLmProvider) p.warmUp()
        val r = ai.forge.evaluation.LatencyProbe.measure(p, "Explique en trois phrases pourquoi le ciel est bleu.")
        r.failed?.let { return "Échec du test : $it" }
        val writing = (r.totalMs - (r.firstTokenMs ?: 0)).coerceAtLeast(1)
        val wps = r.chars / 6.0 * 1000 / writing
        return "Premier mot : ${r.firstTokenMs ?: "?"} ms · écriture : ${"%.1f".format(wps)} mots/s · ${brainDetail.value ?: ""}"
    }

    private fun buildOrchestrator(): Orchestrator {
        listOf("oai", "anthropic").forEach { usage.setLimits(it, UsageLimits(settings.cloudDailyRequestLimit, settings.cloudMonthlyBudget)) }
        return Orchestrator(
            ProviderRouter(providers(), RoutingPolicy(localOnly = settings.localOnly, networkAvailable = networkAvailable.value), egress(), usage),
            memory,
            actionPlanner = ActionPlanner { tools.specs() },
            actionExecutor = actionExecutor,
            researchTool = researchTool(),
            libraryTool = library.tool(),
            marketTool = marketTool(),
        ).also { o -> o.onTurnMetrics = { m -> turnMetrics.value = (listOf(m) + turnMetrics.value).take(30) } }
    }

    /** Fournisseur de recherche configuré, ou null (hors-ligne, non configuré, ou tiers en mode local strict). */
    fun searchProvider(): SearchProvider? {
        if (!networkAvailable.value) return null
        settings.searxngUrl?.takeIf { it.isNotBlank() }?.let { return SearxngSearchProvider(it) }
        if (settings.localOnly) return null
        keys.get("brave")?.let { return BraveSearchProvider(it) }
        // Par défaut, sans aucun réglage : Wikipédia + wikis Marvel et DC (API publiques, sans clé).
        return ai.forge.browser.CompositeSearchProvider(
            general = listOf(ai.forge.browser.MediaWikiSearchProvider.wikipediaFr()),
            comics = listOf(ai.forge.browser.MediaWikiSearchProvider.marvelWiki(), ai.forge.browser.MediaWikiSearchProvider.dcWiki()),
        )
    }

    private fun researchTool(): ResearchTool? {
        val provider = searchProvider() ?: return null
        val key = provider.id + "|" + provider.displayName
        val session = researchSession?.takeIf { it.first == key }?.second
            ?: ResearchSession(provider, pageFetcher).also { researchSession = key to it }
        return ResearchTool { q ->
            // Rapide : 3 sources, extraits courts — chaque caractère est relu par le modèle embarqué.
            val d = session.run(q, maxSources = 3, excerptChars = 900)
            ResearchContext(d.promptContext, d.bibliography(), d.sources.size, d.refused.size, d.sources.count { it.suspicious },
                d.sources.map { it.citation.title to it.citation.url })
        }
    }

    private val marketCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String>>()

    /** Analyse YouTube (API officielle, clé gratuite de l'utilisateur) ; résultats gardés 1 h. */
    private fun marketTool(): ai.forge.reasoning.MarketTool? {
        val key = keys.get("youtube") ?: return null
        if (!networkAvailable.value || settings.localOnly) return null
        val yt = ai.forge.market.YouTubeDataClient(key)
        return ai.forge.reasoning.MarketTool { topic, platform ->
            val q = topic.replace(Regex("(?i)(écris|rédige|fais|un|une|script|vidéo|video|tiktok|short|youtube|sur|analyse|le|la|les|marché|de|des|du|moi|-moi)\\b"), " ")
                .replace(Regex("\\s+"), " ").trim().ifBlank { topic }
            marketCache[q]?.takeIf { System.currentTimeMillis() - it.first < 3_600_000 }?.second ?: run {
                val vids = yt.topVideos(q, java.time.Instant.now().minus(java.time.Duration.ofDays(180)))
                val filtered = if (platform.vertical) vids.filter { it.isShort }.ifEmpty { vids } else vids.filter { !it.isShort }.ifEmpty { vids }
                ai.forge.market.MarketAnalyzer.analyze(q, filtered).summary().also { marketCache[q] = System.currentTimeMillis() to it }
            }
        }
    }

    private fun rebuild() { orchestrator = buildOrchestrator() }

    /** À appeler quand les outils changent (bibliothèque indexée, recherche configurée…). */
    fun refreshTools() = rebuild()

    fun updateSettings(new: ForgeSettings) {
        settings = new
        new.save(app)
        gate.ceiling = new.autonomyCeiling
        applyVoice()
        ai.forge.app.academy.BriefingWorker.schedule(app, new.morningBriefing)
        voice.requireWakeWord = new.requireWakeWord
        voice.bargeInEnabled = new.bargeInWithoutHeadset
        orchestrator = buildOrchestrator()
        if (brain.value !is BrainState.Downloading) brain.value = if (providers().isEmpty()) BrainState.Missing else BrainState.Ready
    }

    /** État du « cerveau » (modèle de langage), affiché clairement à l'utilisateur. */
    sealed interface BrainState {
        data object Missing : BrainState
        data class Downloading(val downloaded: Long, val total: Long) : BrainState
        data class Error(val message: String) : BrainState
        data object Ready : BrainState
    }
    val brain = MutableStateFlow<BrainState>(if (providers().isEmpty()) BrainState.Missing else BrainState.Ready)

    /** Réseau facturé au volume (données mobiles) : on prévient avant 1,6 Go. */
    fun onMeteredNetwork(): Boolean = app.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered

    /** Télécharge un modèle du catalogue (libre, sans compte), le vérifie puis l'active. */
    fun installBrain(model: DownloadableModel = DownloadableModel.RECOMMENDED) {
        if (brain.value is BrainState.Downloading) return
        brain.value = BrainState.Downloading(0, model.sizeBytes)
        scope.launch {
            val r = ModelDownloader().download(model, java.io.File(app.filesDir, "models")) { done, total ->
                brain.value = BrainState.Downloading(done, total)
            }
            when (r) {
                is ModelDownloader.Result.Ready -> {
                    updateSettings(settings.copy(localModelPath = r.file.absolutePath)); brain.value = BrainState.Ready
                    warmUpLocalModel()
                }
                is ModelDownloader.Result.Failed -> brain.value = BrainState.Error(r.reason)
            }
        }
    }

    private val feedbackLog = ai.forge.evaluation.FeedbackLog()

    /**
     * Retour de l'utilisateur sur une réponse. Un retour négatif commenté devient une leçon
     * appliquée aux réponses suivantes (pas de réentraînement du modèle : ses instructions évoluent).
     */
    fun recordFeedback(specialist: String, request: String, response: String, kind: ai.forge.evaluation.FeedbackKind, comment: String?) {
        val f = ai.forge.evaluation.Feedback(java.util.UUID.randomUUID().toString(), conversation.id, specialist, "v1", kind,
            request, response, comment?.ifBlank { null }, System.currentTimeMillis())
        feedbackLog.add(f)
        feedbackLog.lessonFrom(f)?.let { scope.launch { memory.remember(it, confirmed = true) } }
    }

    /** Voix : préférences + meilleure voix selon la connexion (appelé au démarrage et au changement de réseau). */
    fun applyVoice() {
        // Voix neuronale hors-ligne si installée ; sinon voix du système.
        val path = settings.neuralVoicePath?.takeIf { java.io.File(it).canRead() }
        val current = voice.neural
        if (path == null) { current?.close(); voice.neural = null }
        else if (current == null) voice.neural = ai.forge.app.voice.NeuralTtsOutput(path, app.cacheDir.absolutePath, settings.speechRate).also { it.warmUp() }
        else current.speakingRate = (settings.speechRate / 1.15f).coerceIn(0.7f, 2.0f)
        voice.tts.preferredVoice = settings.voiceName
        voice.tts.allowNetworkVoices = settings.networkVoices
        voice.tts.speechRate = settings.speechRate
        scope.launch { voice.tts.selectBestVoice(networkAvailable.value) }
    }

    /** Dossier du modèle de mot d'appel installé, ou null. */
    fun wakeModelDir(): String? = java.io.File(app.filesDir, "wake/vosk-model-small-fr-0.22").takeIf { java.io.File(it, "conf/model.conf").exists() }?.absolutePath

    val wakeInstall = MutableStateFlow<String?>(null)
    /** Installation du mot d'appel « Forge » (42 Mo) : téléchargement vérifié, décompression sûre. */
    fun installWakeWord(onReady: () -> Unit) {
        val m = DownloadableModel.VOSK_FR
        scope.launch {
            wakeInstall.value = "Téléchargement… 0 %"
            val dir = java.io.File(app.filesDir, "wake")
            when (val r = ModelDownloader().download(m, dir) { d, t -> wakeInstall.value = "Téléchargement… ${d * 100 / t} %" }) {
                is ModelDownloader.Result.Ready -> {
                    wakeInstall.value = "Installation…"
                    runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ModelDownloader.unzipSafely(r.file, dir); r.file.delete() } }
                        .onSuccess { wakeInstall.value = "Mot d'appel installé."; onReady() }
                        .onFailure { wakeInstall.value = "Installation impossible : ${it.message}" }
                }
                is ModelDownloader.Result.Failed -> wakeInstall.value = r.reason
            }
        }
    }

    /** Installation de la voix neuronale (127 Mo) : téléchargement vérifié puis activation. */
    val neuralVoiceInstall = MutableStateFlow<String?>(null)
    fun installNeuralVoice() {
        val m = DownloadableModel.POCKET_TTS_FR
        scope.launch {
            neuralVoiceInstall.value = "Téléchargement de la voix… 0 %"
            when (val r = ModelDownloader().download(m, java.io.File(app.filesDir, "voices")) { d, t -> neuralVoiceInstall.value = "Téléchargement de la voix… ${d * 100 / t} %" }) {
                is ModelDownloader.Result.Ready -> { updateSettings(settings.copy(neuralVoicePath = r.file.absolutePath)); neuralVoiceInstall.value = "Voix Estelle installée et activée." }
                is ModelDownloader.Result.Failed -> neuralVoiceInstall.value = r.reason
            }
        }
    }

    fun newConversation() { conversation = ConversationContext(UUID.randomUUID().toString()) }

    /** Reprend une conversation enregistrée ; renvoie ses messages pour l'affichage. */
    suspend fun resumeConversation(id: String): List<ai.forge.app.history.StoredMessage> {
        val (ctx, msgs) = history.restore(id)
        conversation = ctx
        return msgs
    }

    // Déclaré en dernier : le rappel réseau peut arriver immédiatement et utilise tout ce qui précède.
    init {
        // Scénario H : suivre la connectivité pour basculer en local et signaler ce qui ne marche plus.
        val cm = app.getSystemService(ConnectivityManager::class.java)
        networkAvailable.value = cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        rebuild()
        applyVoice()
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { networkAvailable.value = true; rebuild(); applyVoice() }
            override fun onLost(network: Network) { networkAvailable.value = false; rebuild(); applyVoice() }
        })
    }
}

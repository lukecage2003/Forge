package ai.forge.app

import ai.forge.app.actions.CalendarDraftTool
import ai.forge.app.actions.OpenAppTool
import ai.forge.app.actions.SetAlarmTool
import ai.forge.app.actions.ShareTextTool
import ai.forge.app.llm.MediaPipeLocalProvider
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
import ai.forge.actions.ActionStatus
import ai.forge.actions.ActionEngine
import ai.forge.models.UsageLimits
import ai.forge.models.UsageMeter
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

class ForgeApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Assemblage des dépendances (injection manuelle : simple, explicite, sans magie). */
class AppContainer(private val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val keys = SecureKeyStore(app)
    var settings: ForgeSettings = ForgeSettings.load(app)
        private set

    private val db = ForgeDatabase.open(app)
    // Embedder lexical de repli ; à remplacer par un embedder sémantique local (voir ROADMAP).
    val memory = MemoryManager(RoomMemoryStore(db.memory()), HashingEmbedder())

    val gate = PermissionGate(settings.autonomyCeiling)
    val tools = ToolRegistry(listOf(SetAlarmTool(app), CalendarDraftTool(app), ShareTextTool(app), OpenAppTool(app)))
    /** Action Center : journal + arrêt d'urgence (§11). */
    val actions = ActionEngine(tools, gate)
    val usage = UsageMeter()
    val networkAvailable = MutableStateFlow(true)

    /** Arrêt d'urgence global : voix ET actions. */
    fun emergencyStop() { voice.stop(); actions.engageKillSwitch() }

    /** Pont entre l'orchestrateur et l'Action Engine : statuts réels, confirmation explicite. */
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

    var conversation = ConversationContext(UUID.randomUUID().toString())
        private set

    var orchestrator: Orchestrator = buildOrchestrator()
        private set

    val voice = VoiceCoordinator(app, scope, { orchestrator }, { conversation })

    fun providers(): List<LlmProvider> = buildList {
        settings.localModelPath?.let { add(MediaPipeLocalProvider(app, it)) }
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

    private fun buildOrchestrator(): Orchestrator {
        listOf("oai", "anthropic").forEach { usage.setLimits(it, UsageLimits(settings.cloudDailyRequestLimit, settings.cloudMonthlyBudget)) }
        return Orchestrator(
            ProviderRouter(providers(), RoutingPolicy(localOnly = settings.localOnly, networkAvailable = networkAvailable.value), egress(), usage),
            memory,
            actionPlanner = ActionPlanner { tools.specs() },
            actionExecutor = actionExecutor,
        )
    }

    private fun rebuild() { orchestrator = buildOrchestrator() }

    fun updateSettings(new: ForgeSettings) {
        settings = new
        new.save(app)
        gate.ceiling = new.autonomyCeiling
        orchestrator = buildOrchestrator()
    }

    fun newConversation() { conversation = ConversationContext(UUID.randomUUID().toString()) }

    // Déclaré en dernier : le rappel réseau peut arriver immédiatement et utilise tout ce qui précède.
    init {
        // Scénario H : suivre la connectivité pour basculer en local et signaler ce qui ne marche plus.
        val cm = app.getSystemService(ConnectivityManager::class.java)
        networkAvailable.value = cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        rebuild()
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { networkAvailable.value = true; rebuild() }
            override fun onLost(network: Network) { networkAvailable.value = false; rebuild() }
        })
    }
}

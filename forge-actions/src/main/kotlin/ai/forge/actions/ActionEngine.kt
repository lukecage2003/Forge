package ai.forge.actions

import ai.forge.security.PermissionGate
import ai.forge.security.ToolCall
import ai.forge.security.ToolRegistry
import ai.forge.security.ToolResult
import ai.forge.security.executeGuarded
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import java.util.UUID

/** Statut d'une action, tel qu'affiché dans l'Action Center. */
@Serializable
enum class ActionStatus { PROPOSED, AWAITING_CONFIRMATION, RUNNING, SUCCEEDED, FAILED, DENIED, CANCELLED, BLOCKED_BY_KILL_SWITCH }

@Serializable
data class ActionLogEntry(
    val id: String,
    val tool: String,
    val arguments: String,
    val rationale: String,
    val status: ActionStatus,
    /** Ce que le système a réellement répondu : jamais reformulé en succès imaginaire. */
    val outcome: String,
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
)

/**
 * Forge Action Engine (§11) : toute action passe par le portail d'autorisation, est journalisée,
 * et peut être stoppée instantanément par l'arrêt d'urgence (kill switch).
 * Quand le kill switch est enclenché, les actions en cours sont annulées et aucune nouvelle action
 * n'est lancée tant que l'utilisateur ne l'a pas explicitement désactivé.
 */
class ActionEngine(
    private val registry: ToolRegistry,
    private val gate: PermissionGate,
    private val now: () -> Long = System::currentTimeMillis,
    parent: Job? = null,
) {
    private val job = SupervisorJob(parent)
    private val scope = CoroutineScope(job)
    private val _log = MutableStateFlow<List<ActionLogEntry>>(emptyList())
    val log: StateFlow<List<ActionLogEntry>> = _log.asStateFlow()
    private val _killed = MutableStateFlow(false)
    val killSwitchEngaged: StateFlow<Boolean> = _killed.asStateFlow()

    /** Arrêt immédiat : annule tout ce qui tourne et bloque les nouvelles actions. */
    fun engageKillSwitch() {
        _killed.value = true
        job.cancelChildren(CancellationException("Arrêt d'urgence"))
    }

    fun releaseKillSwitch() { _killed.value = false }

    /** Confirme une action en attente (confirmation explicite, à usage unique). */
    fun confirm(call: ToolCall) = gate.confirmOnce(call)

    suspend fun run(call: ToolCall): ActionLogEntry {
        val id = UUID.randomUUID().toString()
        val base = ActionLogEntry(id, call.tool, call.arguments.toString(), call.rationale, ActionStatus.RUNNING, "", now())
        if (_killed.value) return append(base.copy(status = ActionStatus.BLOCKED_BY_KILL_SWITCH,
            outcome = "Arrêt d'urgence actif : action non lancée.", endedAtMs = now()))
        append(base)
        val result = try {
            scope.async { registry.executeGuarded(call, gate) }.await()
        } catch (e: CancellationException) {
            return update(id) { it.copy(status = ActionStatus.CANCELLED, outcome = "Annulée par l'arrêt d'urgence.", endedAtMs = now()) }
        }
        return update(id) {
            when (result) {
                is ToolResult.Success -> it.copy(status = ActionStatus.SUCCEEDED, outcome = result.output, endedAtMs = now())
                is ToolResult.Error -> it.copy(status = ActionStatus.FAILED, outcome = result.message, endedAtMs = now())
                is ToolResult.Denied -> it.copy(status = ActionStatus.DENIED, outcome = result.reason, endedAtMs = now())
                is ToolResult.NeedsApproval -> it.copy(status = ActionStatus.AWAITING_CONFIRMATION, outcome = result.reason, endedAtMs = now())
            }
        }
    }

    private fun append(e: ActionLogEntry): ActionLogEntry { _log.update { it + e }; return e }
    private fun update(id: String, f: (ActionLogEntry) -> ActionLogEntry): ActionLogEntry {
        var out: ActionLogEntry? = null
        _log.update { list -> list.map { if (it.id == id) f(it).also { n -> out = n } else it } }
        return out!!
    }
}

/**
 * Catalogue des mécanismes Android étudiés (§11) : ce que Forge peut faire, et par quel moyen.
 * Sert à répondre honnêtement à « peux-tu faire X sur mon téléphone ? ».
 */
enum class AndroidMechanism(val label: String, val scope: String, val requiresSpecialPermission: Boolean) {
    INTENTS("Intents Android", "Ouvrir une app, préparer un message, un réveil, un événement : l'app cible garde la validation", false),
    APP_LINKS("App Links", "Ouvrir un contenu précis dans l'app concernée", false),
    SAF("Storage Access Framework", "Lire/organiser les fichiers que l'utilisateur sélectionne", false),
    NOTIFICATIONS("Notifications", "Rappels et résultats, avec permission POST_NOTIFICATIONS", true),
    WORK_MANAGER("WorkManager", "Tâches différées et indexation en arrière-plan, sous contraintes batterie", false),
    APP_FUNCTIONS("AppFunctions", "Fonctions exposées par d'autres apps (Android 16+, adoption encore limitée)", true),
    ACCESSIBILITY("Service d'accessibilité", "Dernier recours, justifié et activé par l'utilisateur ; non utilisé par défaut", true),
}

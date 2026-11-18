package ai.forge.models

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** État thermique, aligné sur PowerManager.THERMAL_STATUS_* d'Android. */
enum class ThermalState { NONE, LIGHT, MODERATE, SEVERE, CRITICAL }

/** Signaux de l'appareil (fournis par Android : ActivityManager, BatteryManager, PowerManager). */
data class DeviceSignals(
    val availableRamMb: Int,
    val batteryPercent: Int,
    val charging: Boolean,
    val thermal: ThermalState,
)

/** Un modèle local et son coût mémoire mesuré ou estimé. */
data class LocalModelSpec(val id: String, val ramMb: Int, val role: String)

/** Handle de chargement réel (MediaPipe, llama.cpp…). */
interface LoadableModel {
    val spec: LocalModelSpec
    suspend fun load()
    suspend fun unload()
}

/**
 * Gestion des ressources (§13.2) — principes :
 * - chargement à la demande, un nombre limité de modèles résidents (budget RAM) ;
 * - déchargement des modèles inactifs (LRU + délai d'inactivité) ;
 * - refus de charger un gros modèle en surchauffe ou batterie faible, avec alternative proposée ;
 * - la tâche active est prioritaire : un modèle en cours d'utilisation n'est jamais déchargé.
 *
 * Un téléphone ne peut pas tout faire tourner en même temps : ce gestionnaire le rend explicite.
 */
class ModelResourceManager(
    private val ramBudgetMb: Int,
    private val idleUnloadMs: Long = 5 * 60_000L,
    private val lowBatteryPercent: Int = 15,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class Resident(val model: LoadableModel, var lastUsedMs: Long, var inUse: Int)
    private val mutex = Mutex()
    private val resident = linkedMapOf<String, Resident>()

    sealed interface Decision {
        data class Ready(val model: LoadableModel) : Decision
        data class Refused(val reason: String, val suggestion: String) : Decision
    }

    fun residentIds(): List<String> = resident.keys.toList()
    fun usedRamMb(): Int = resident.values.sumOf { it.model.spec.ramMb }

    /** Effective budget, réduit en cas de chauffe. */
    fun effectiveBudgetMb(signals: DeviceSignals): Int = when (signals.thermal) {
        ThermalState.NONE, ThermalState.LIGHT -> ramBudgetMb
        ThermalState.MODERATE -> ramBudgetMb * 3 / 4
        ThermalState.SEVERE -> ramBudgetMb / 2
        ThermalState.CRITICAL -> 0
    }.coerceAtMost(signals.availableRamMb)

    suspend fun acquire(model: LoadableModel, signals: DeviceSignals): Decision = mutex.withLock {
        resident[model.spec.id]?.let { it.inUse++; it.lastUsedMs = now(); return Decision.Ready(it.model) }

        if (signals.thermal >= ThermalState.CRITICAL) return Decision.Refused(
            "Appareil en surchauffe critique.", "Attendre le refroidissement ou utiliser le serveur domestique.")
        if (!signals.charging && signals.batteryPercent < lowBatteryPercent && model.spec.ramMb > 1_500) return Decision.Refused(
            "Batterie faible (${signals.batteryPercent} %).", "Utiliser un modèle plus léger ou brancher le téléphone.")

        val budget = effectiveBudgetMb(signals)
        if (model.spec.ramMb > budget) return Decision.Refused(
            "Mémoire insuffisante : ${model.spec.ramMb} Mo requis, ${budget} Mo disponibles pour Forge.",
            "Choisir un modèle plus petit ou le serveur domestique.")

        // Libère les modèles inactifs, du moins récemment utilisé au plus récent.
        val evictable = resident.values.filter { it.inUse == 0 }.sortedBy { it.lastUsedMs }.toMutableList()
        while (usedRamMb() + model.spec.ramMb > budget) {
            val victim = evictable.removeFirstOrNull() ?: return Decision.Refused(
                "Les modèles actuellement utilisés occupent la mémoire.", "Réessayer à la fin de la tâche en cours.")
            victim.model.unload(); resident.remove(victim.model.spec.id)
        }
        model.load()
        resident[model.spec.id] = Resident(model, now(), 1)
        Decision.Ready(model)
    }

    suspend fun release(modelId: String) = mutex.withLock {
        resident[modelId]?.let { it.inUse = (it.inUse - 1).coerceAtLeast(0); it.lastUsedMs = now() }
    }

    /** À appeler périodiquement (ou sur onTrimMemory) : décharge les modèles inactifs. */
    suspend fun trimIdle(force: Boolean = false): List<String> = mutex.withLock {
        val victims = resident.values.filter { it.inUse == 0 && (force || now() - it.lastUsedMs >= idleUnloadMs) }
        victims.forEach { it.model.unload(); resident.remove(it.model.spec.id) }
        victims.map { it.model.spec.id }
    }
}

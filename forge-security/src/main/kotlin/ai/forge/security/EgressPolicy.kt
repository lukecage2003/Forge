package ai.forge.security

import ai.forge.core.model.DataClass
import ai.forge.core.model.TaskCategory

/**
 * Politique de sortie des données (§14) : ce qu'un fournisseur EXTERNE a le droit de recevoir.
 * Les moteurs sur l'appareil ou sur le réseau domestique de l'utilisateur ne sont pas concernés.
 *
 * Par défaut, un fournisseur cloud ne reçoit que des demandes « seules » (sans mémoire ni document),
 * et seulement pour les tâches que l'utilisateur a cochées. Tout élargissement est explicite.
 */
data class ExternalProviderGrant(
    val providerId: String,
    val allowedTasks: Set<TaskCategory> = setOf(TaskCategory.CONVERSATION, TaskCategory.REASONING, TaskCategory.WRITING),
    /** Classe de données maximale autorisée. SECRET n'est jamais accepté. */
    val maxDataClass: DataClass = DataClass.GENERAL,
)

class EgressPolicy(grants: Collection<ExternalProviderGrant> = emptyList()) {
    private val grants = grants.associateBy { it.providerId }.toMutableMap()

    fun grant(g: ExternalProviderGrant) { grants[g.providerId] = g }
    fun revoke(providerId: String) { grants.remove(providerId) }
    fun grantFor(providerId: String) = grants[providerId]

    sealed interface Verdict {
        data object Allowed : Verdict
        data class Refused(val reason: String) : Verdict
    }

    fun check(providerId: String, task: TaskCategory, data: DataClass): Verdict {
        if (data == DataClass.SECRET) return Verdict.Refused("Les secrets ne quittent jamais l'appareil.")
        val g = grants[providerId] ?: return Verdict.Refused("Aucune autorisation n'a été donnée au fournisseur externe « $providerId ».")
        if (task !in g.allowedTasks) return Verdict.Refused("La tâche « ${task.label} » n'est pas autorisée pour « $providerId ».")
        if (data > g.maxDataClass) return Verdict.Refused(
            "« $providerId » n'est autorisé qu'à recevoir : ${g.maxDataClass.label}. Cette requête contient : ${data.label}.")
        return Verdict.Allowed
    }
}

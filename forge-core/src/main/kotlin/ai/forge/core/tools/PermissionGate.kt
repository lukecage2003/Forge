package ai.forge.core.tools

import ai.forge.core.model.AutonomyLevel
import java.time.Instant

/** Automatisation approuvée à l'avance par l'utilisateur (niveau 3). */
data class StandingApproval(val toolName: String, val note: String, val expiresAt: Instant? = null)

/** Trace d'audit : chaque décision du portail est consignée (transparence et débogage). */
data class AuditEntry(val at: Instant, val tool: String, val decision: String, val rationale: String)

/**
 * Portail d'autorisation (§1.3). Règles :
 * - L'utilisateur fixe un plafond d'autonomie ([ceiling]).
 * - Niveau 4 (sensible) ou action irréversible ou effet externe : toujours confirmation explicite.
 * - Niveau 3 : uniquement si une approbation permanente non expirée existe.
 * - Rien n'est jamais exécuté au-delà du plafond.
 */
class PermissionGate(
    var ceiling: AutonomyLevel = AutonomyLevel.L2_REVERSIBLE,
    private val clock: () -> Instant = Instant::now,
) {
    private val standing = mutableMapOf<String, StandingApproval>()
    private val oneShot = mutableSetOf<String>()
    private val _audit = mutableListOf<AuditEntry>()
    val audit: List<AuditEntry> get() = _audit

    fun grantStanding(approval: StandingApproval) { standing[approval.toolName] = approval }
    fun revokeStanding(toolName: String) { standing.remove(toolName) }

    /** Confirmation explicite, valable pour un seul appel. */
    fun confirmOnce(call: ToolCall) { oneShot += key(call) }

    sealed interface Decision {
        data object Allow : Decision
        data class Ask(val reason: String) : Decision
        data class Deny(val reason: String) : Decision
    }

    fun evaluate(spec: ToolSpec, call: ToolCall): Decision {
        val decision = decide(spec, call)
        _audit += AuditEntry(clock(), spec.name, decision.toString(), call.rationale)
        if (decision == Decision.Allow) oneShot.remove(key(call))
        return decision
    }

    private fun decide(spec: ToolSpec, call: ToolCall): Decision {
        if (spec.requiredLevel > ceiling) {
            return Decision.Deny("« ${spec.name} » requiert le niveau ${spec.requiredLevel.label}, au-delà du plafond autorisé (${ceiling.label}).")
        }
        val confirmed = key(call) in oneShot
        val sensitive = spec.requiredLevel == AutonomyLevel.L4_SENSITIVE || !spec.reversible || spec.externalEffect
        if (sensitive) {
            return if (confirmed) Decision.Allow
            else Decision.Ask("Action sensible ou irréversible : confirmation explicite requise.")
        }
        return when (spec.requiredLevel) {
            AutonomyLevel.L0_CONVERSATION, AutonomyLevel.L1_PROPOSE, AutonomyLevel.L2_REVERSIBLE -> Decision.Allow
            AutonomyLevel.L3_PREAPPROVED -> {
                val s = standing[spec.name]
                when {
                    confirmed -> Decision.Allow
                    s != null && (s.expiresAt == null || s.expiresAt.isAfter(clock())) -> Decision.Allow
                    else -> Decision.Ask("Aucune automatisation approuvée pour « ${spec.name} ».")
                }
            }
            AutonomyLevel.L4_SENSITIVE -> Decision.Ask("Confirmation requise.")
        }
    }

    private fun key(call: ToolCall) = call.tool + "|" + call.arguments.toString()
}

/** Exécute un appel d'outil en passant obligatoirement par le portail. */
suspend fun ToolRegistry.executeGuarded(call: ToolCall, gate: PermissionGate): ToolResult {
    val tool = this[call.tool] ?: return ToolResult.Error("Outil inconnu : ${call.tool}")
    return when (val d = gate.evaluate(tool.spec, call)) {
        PermissionGate.Decision.Allow -> runCatching { tool.execute(call.arguments) }
            .getOrElse { ToolResult.Error(it.message ?: it.toString()) }
        is PermissionGate.Decision.Ask -> ToolResult.NeedsApproval(call, d.reason)
        is PermissionGate.Decision.Deny -> ToolResult.Denied(d.reason)
    }
}

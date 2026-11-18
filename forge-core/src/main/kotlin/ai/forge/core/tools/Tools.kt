package ai.forge.core.tools

import ai.forge.core.model.AutonomyLevel
import kotlinx.serialization.json.JsonObject

/** Description d'un outil exposé aux agents. */
data class ToolSpec(
    val name: String,
    val description: String,
    /** Niveau minimal requis pour exécuter l'outil. */
    val requiredLevel: AutonomyLevel,
    val reversible: Boolean,
    /** Indique un effet hors de l'appareil (envoi, publication, paiement…). */
    val externalEffect: Boolean = false,
)

data class ToolCall(val tool: String, val arguments: JsonObject, val rationale: String)

sealed interface ToolResult {
    data class Success(val output: String) : ToolResult
    data class Error(val message: String) : ToolResult
    data class Denied(val reason: String) : ToolResult
    data class NeedsApproval(val call: ToolCall, val reason: String) : ToolResult
}

interface Tool {
    val spec: ToolSpec
    suspend fun execute(arguments: JsonObject): ToolResult
}

class ToolRegistry(tools: List<Tool> = emptyList()) {
    private val byName = tools.associateBy { it.spec.name }.toMutableMap()
    fun register(tool: Tool) { byName[tool.spec.name] = tool }
    operator fun get(name: String): Tool? = byName[name]
    fun specs(): List<ToolSpec> = byName.values.map { it.spec }
}

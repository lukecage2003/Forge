package ai.forge.core

import ai.forge.core.model.AutonomyLevel
import ai.forge.core.tools.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import kotlin.test.*

class PermissionGateTest {
    private fun tool(name: String, level: AutonomyLevel, reversible: Boolean = true, external: Boolean = false) = object : Tool {
        override val spec = ToolSpec(name, name, level, reversible, external)
        var runs = 0
        override suspend fun execute(arguments: JsonObject): ToolResult { runs++; return ToolResult.Success("ok") }
    }
    private fun call(name: String) = ToolCall(name, JsonObject(mapOf("x" to JsonPrimitive(1))), "test")

    @Test fun reversibleActionAllowedUnderCeiling() = runTest {
        val t = tool("alarm", AutonomyLevel.L2_REVERSIBLE)
        val gate = PermissionGate(ceiling = AutonomyLevel.L2_REVERSIBLE)
        assertIs<ToolResult.Success>(ToolRegistry(listOf(t)).executeGuarded(call("alarm"), gate))
    }

    @Test fun actionAboveCeilingDenied() = runTest {
        val gate = PermissionGate(ceiling = AutonomyLevel.L1_PROPOSE)
        val r = ToolRegistry(listOf(tool("alarm", AutonomyLevel.L2_REVERSIBLE))).executeGuarded(call("alarm"), gate)
        assertIs<ToolResult.Denied>(r)
    }

    @Test fun irreversibleActionAlwaysNeedsExplicitConfirmationOnce() = runTest {
        val t = tool("send_sms", AutonomyLevel.L2_REVERSIBLE, reversible = false, external = true)
        val reg = ToolRegistry(listOf(t))
        val gate = PermissionGate(ceiling = AutonomyLevel.L4_SENSITIVE)
        assertIs<ToolResult.NeedsApproval>(reg.executeGuarded(call("send_sms"), gate))
        gate.confirmOnce(call("send_sms"))
        assertIs<ToolResult.Success>(reg.executeGuarded(call("send_sms"), gate))
        // La confirmation est consommée : un second envoi redemande l'accord.
        assertIs<ToolResult.NeedsApproval>(reg.executeGuarded(call("send_sms"), gate))
    }

    @Test fun preapprovedAutomationRespectsExpiry() = runTest {
        var now = Instant.parse("2026-01-01T00:00:00Z")
        val gate = PermissionGate(ceiling = AutonomyLevel.L3_PREAPPROVED, clock = { now })
        val reg = ToolRegistry(listOf(tool("daily_digest", AutonomyLevel.L3_PREAPPROVED)))
        assertIs<ToolResult.NeedsApproval>(reg.executeGuarded(call("daily_digest"), gate))
        gate.grantStanding(StandingApproval("daily_digest", "chaque matin", expiresAt = now.plusSeconds(3600)))
        assertIs<ToolResult.Success>(reg.executeGuarded(call("daily_digest"), gate))
        now = now.plusSeconds(7200)
        assertIs<ToolResult.NeedsApproval>(reg.executeGuarded(call("daily_digest"), gate))
        assertTrue(gate.audit.size >= 3)
    }
}

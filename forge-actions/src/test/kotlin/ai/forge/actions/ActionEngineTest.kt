package ai.forge.actions

import ai.forge.core.model.AutonomyLevel
import ai.forge.security.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.*

class ActionEngineTest {
    private fun tool(name: String, level: AutonomyLevel, reversible: Boolean = true, body: suspend () -> ToolResult) = object : Tool {
        override val spec = ToolSpec(name, name, level, reversible)
        override suspend fun execute(arguments: JsonObject) = body()
    }
    private fun call(name: String) = ToolCall(name, JsonObject(emptyMap()), "demande utilisateur")

    @Test fun journalRecordsRealOutcomes() = runTest {
        val reg = ToolRegistry(listOf(
            tool("ok", AutonomyLevel.L2_REVERSIBLE) { ToolResult.Success("Réveil transmis") },
            tool("ko", AutonomyLevel.L2_REVERSIBLE) { ToolResult.Error("Aucune application") },
            tool("irrev", AutonomyLevel.L2_REVERSIBLE, reversible = false) { ToolResult.Success("envoyé") },
        ))
        val engine = ActionEngine(reg, PermissionGate(AutonomyLevel.L2_REVERSIBLE))
        assertEquals(ActionStatus.SUCCEEDED, engine.run(call("ok")).status)
        assertEquals(ActionStatus.FAILED, engine.run(call("ko")).status)
        assertEquals(ActionStatus.AWAITING_CONFIRMATION, engine.run(call("irrev")).status)
        engine.confirm(call("irrev"))
        assertEquals(ActionStatus.SUCCEEDED, engine.run(call("irrev")).status)
        assertEquals(4, engine.log.value.size)
    }

    @Test fun killSwitchCancelsRunningAndBlocksNew() = runTest {
        val started = CompletableDeferred<Unit>()
        val reg = ToolRegistry(listOf(tool("long", AutonomyLevel.L2_REVERSIBLE) {
            started.complete(Unit); awaitCancellation()
        }))
        val engine = ActionEngine(reg, PermissionGate(AutonomyLevel.L2_REVERSIBLE))
        val running = async(Dispatchers.Default) { engine.run(call("long")) }
        started.await()
        engine.engageKillSwitch()
        assertEquals(ActionStatus.CANCELLED, running.await().status)
        assertEquals(ActionStatus.BLOCKED_BY_KILL_SWITCH, engine.run(call("long")).status)
        engine.releaseKillSwitch()
        assertFalse(engine.killSwitchEngaged.value)
    }
}

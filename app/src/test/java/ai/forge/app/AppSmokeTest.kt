package ai.forge.app

import ai.forge.actions.ActionStatus
import ai.forge.reasoning.ActionOutcome
import ai.forge.reasoning.OrchestratorEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppSmokeTest {
    private val app get() = ApplicationProvider.getApplicationContext<ForgeApplication>()

    @Test fun applicationAndMainScreenStartWithoutCrash() {
        assertNotNull(app.container.orchestrator)
        ActivityScenario.launch(MainActivity::class.java).use { s -> s.onActivity { assertTrue(!it.isFinishing) } }
    }

    /** Scénario G dans le vrai conteneur : portail réel, journal réel, Intent réellement émis. */
    @Test fun scenarioG_alarmGoesThroughRealActionEngine() = runBlocking {
        val c = app.container
        val events = c.orchestrator.handle("Mets un réveil à 7h15", c.conversation, voice = false).toList()
        val result = events.filterIsInstance<OrchestratorEvent.ActionResult>().single()
        assertEquals(ActionOutcome.Status.DONE, result.outcome.status, result.outcome.detail)
        assertEquals(ActionStatus.SUCCEEDED, c.actions.log.value.single().status)
        val intent = shadowOf(app).nextStartedActivity
        assertEquals(android.provider.AlarmClock.ACTION_SET_ALARM, intent.action)
        assertEquals(7, intent.getIntExtra(android.provider.AlarmClock.EXTRA_HOUR, -1))
        assertEquals(15, intent.getIntExtra(android.provider.AlarmClock.EXTRA_MINUTES, -1))

        // Arrêt d'urgence : plus aucune action.
        c.emergencyStop()
        val blocked = c.orchestrator.handle("Mets un réveil à 8h", c.conversation, voice = false).toList()
            .filterIsInstance<OrchestratorEvent.ActionResult>().single()
        assertEquals(ActionOutcome.Status.BLOCKED, blocked.outcome.status)
    }

    @Test fun withoutAnyEngineConversationFailsHonestly() = runBlocking {
        val c = app.container
        val last = c.orchestrator.handle("Bonjour Forge", c.conversation, voice = false).toList().last()
        assertTrue(last is OrchestratorEvent.Failed && last.reason.contains("Aucun moteur"))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BrainStateTest {
    @Test fun freshInstallShowsMissingBrainAndConfiguringAProviderMakesItReady() {
        val c = ApplicationProvider.getApplicationContext<ForgeApplication>().container
        assertEquals(AppContainer.BrainState.Missing, c.brain.value)
        c.updateSettings(c.settings.copy(lanServerUrl = "http://192.168.1.10:11434/v1", lanModel = "qwen3:8b"))
        assertEquals(AppContainer.BrainState.Ready, c.brain.value)
    }
}

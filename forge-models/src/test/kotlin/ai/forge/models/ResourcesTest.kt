package ai.forge.models

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ResourcesTest {
    private class Fake(id: String, ram: Int) : LoadableModel {
        override val spec = LocalModelSpec(id, ram, "test")
        var loaded = false
        override suspend fun load() { loaded = true }
        override suspend fun unload() { loaded = false }
    }
    private val ok = DeviceSignals(availableRamMb = 6_000, batteryPercent = 80, charging = false, thermal = ThermalState.NONE)

    @Test fun loadsOnDemandAndEvictsLeastRecentlyUsedIdleModel() = runTest {
        var t = 0L
        val rm = ModelResourceManager(ramBudgetMb = 4_000, now = { t })
        val a = Fake("llm-2b", 2_500); val b = Fake("embed", 300); val c = Fake("llm-4b", 3_500)
        assertIs<ModelResourceManager.Decision.Ready>(rm.acquire(a, ok)); rm.release("llm-2b")
        t = 10; assertIs<ModelResourceManager.Decision.Ready>(rm.acquire(b, ok)); rm.release("embed")
        t = 20; assertIs<ModelResourceManager.Decision.Ready>(rm.acquire(c, ok))
        assertFalse(a.loaded); assertTrue(c.loaded)
        assertTrue(rm.usedRamMb() <= 4_000)
    }

    @Test fun neverEvictsModelInUse() = runTest {
        val rm = ModelResourceManager(ramBudgetMb = 4_000)
        rm.acquire(Fake("a", 3_000), ok)
        val r = rm.acquire(Fake("b", 2_000), ok)
        assertIs<ModelResourceManager.Decision.Refused>(r)
        assertEquals(listOf("a"), rm.residentIds())
    }

    @Test fun thermalAndBatteryProtection() = runTest {
        val rm = ModelResourceManager(ramBudgetMb = 4_000)
        val hot = ok.copy(thermal = ThermalState.SEVERE)
        assertEquals(2_000, rm.effectiveBudgetMb(hot))
        assertIs<ModelResourceManager.Decision.Refused>(rm.acquire(Fake("big", 3_000), hot))
        assertIs<ModelResourceManager.Decision.Refused>(rm.acquire(Fake("big", 3_000), ok.copy(batteryPercent = 9)))
        assertIs<ModelResourceManager.Decision.Ready>(rm.acquire(Fake("big", 3_000), ok.copy(batteryPercent = 9, charging = true)))
    }

    @Test fun idleModelsAreUnloaded() = runTest {
        var t = 0L
        val rm = ModelResourceManager(ramBudgetMb = 4_000, idleUnloadMs = 1_000, now = { t })
        rm.acquire(Fake("a", 1_000), ok); rm.release("a")
        t = 500; assertTrue(rm.trimIdle().isEmpty())
        t = 2_000; assertEquals(listOf("a"), rm.trimIdle())
    }

    @Test fun offlineSwitchesToLocalEnginesAndFlagsTheRest() {
        val reg = EngineRegistry()
        reg.register(EngineDescriptor(EngineKind.LLM, "cloud", "Cloud", requiresNetwork = true, external = true), "c")
        reg.register(EngineDescriptor(EngineKind.LLM, "gemma", "Gemma local", requiresNetwork = false, external = false), "g")
        reg.register(EngineDescriptor(EngineKind.SEARCH, "searx", "Recherche Web", requiresNetwork = true, external = true), "s")
        val report = reg.offlineReport(networkAvailable = false)
        val llm = report.single { it.kind == EngineKind.LLM }
        assertTrue(llm.available); assertEquals("Gemma local", llm.engine)
        assertEquals("g", reg.active<String>(EngineKind.LLM))
        assertFalse(report.single { it.kind == EngineKind.SEARCH }.available)
    }
}

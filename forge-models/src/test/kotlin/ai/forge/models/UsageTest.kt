package ai.forge.models

import ai.forge.core.model.ThinkingDepth
import ai.forge.security.EgressPolicy
import ai.forge.security.ExternalProviderGrant
import ai.forge.core.model.TaskCategory
import kotlinx.coroutines.test.runTest
import java.time.ZoneOffset
import kotlin.test.*

class UsageTest {
    @Test fun costIsEstimatedAndLimitsBlockProvider() = runTest {
        val meter = UsageMeter(ZoneOffset.UTC, now = { 1_700_000_000_000 })
        meter.setPricing("cloud", Pricing("EUR", 3.0, 15.0))
        val r = meter.record("cloud", 1_000_000, 100_000)
        assertEquals(4.5, r.cost!!, 1e-9)
        meter.setLimits("cloud", UsageLimits(maxRequestsPerDay = 1))
        assertNotNull(meter.blockReason("cloud"))
        assertTrue(meter.summary("cloud").contains("estimation"))

        val cloud = ScriptedProvider(id = "cloud", capabilities = ProviderCapabilities(Locality.CLOUD, 100_000, reasoningQuality = 5)) { "" }
        val router = ProviderRouter(listOf(cloud), egress = EgressPolicy(listOf(ExternalProviderGrant("cloud"))), usage = meter)
        val needs = RoutingNeeds(ThinkingDepth.EXPERT, task = TaskCategory.REASONING)
        assertNull(router.select(needs))
        assertTrue(router.explain(needs).contains("limite quotidienne"))
    }

    @Test fun unpricedProviderHasNoInventedCost() {
        val meter = UsageMeter()
        assertNull(meter.record("x", 10, 10).cost)
        assertTrue(meter.summary("x").contains("tarif non renseigné"))
    }
}

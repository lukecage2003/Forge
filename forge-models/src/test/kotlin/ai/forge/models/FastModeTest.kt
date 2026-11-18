package ai.forge.models

import ai.forge.core.model.DataClass
import ai.forge.core.model.TaskCategory
import ai.forge.core.model.ThinkingDepth
import ai.forge.security.EgressPolicy
import ai.forge.security.ExternalProviderGrant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class FastModeTest {
    private fun p(id: String, loc: Locality, q: Int) = object : LlmProvider {
        override val id = id; override val displayName = id
        override val capabilities = ProviderCapabilities(loc, 32_000, reasoningQuality = q)
        override suspend fun isAvailable() = true
        override fun stream(request: ChatRequest): Flow<LlmEvent> = emptyFlow()
    }
    private val phone = p("local", Locality.ON_DEVICE, 4)
    private val gemini = p("oai", Locality.CLOUD, 4)
    private val egress = EgressPolicy(listOf(ExternalProviderGrant("oai", setOf(TaskCategory.CONVERSATION), DataClass.GENERAL)))

    @Test fun fastModeSendsGeneralQuestionsOnlineAndKeepsPrivateDataLocal() = runTest {
        val fast = ProviderRouter(listOf(phone, gemini), RoutingPolicy(preferCloud = true), egress)
        assertEquals("oai", fast.select(RoutingNeeds(ThinkingDepth.STANDARD))!!.id)
        assertEquals("local", fast.select(RoutingNeeds(ThinkingDepth.STANDARD, dataClass = DataClass.PERSONAL))!!.id)
        assertEquals("local", ProviderRouter(listOf(phone, gemini), RoutingPolicy(preferCloud = true, networkAvailable = false), egress)
            .select(RoutingNeeds(ThinkingDepth.STANDARD))!!.id)
        // Sans mode rapide : le téléphone d'abord, comme avant.
        assertEquals("local", ProviderRouter(listOf(phone, gemini), RoutingPolicy(), egress).select(RoutingNeeds(ThinkingDepth.STANDARD))!!.id)
    }
}

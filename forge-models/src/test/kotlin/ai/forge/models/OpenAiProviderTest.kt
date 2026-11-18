package ai.forge.models

import ai.forge.models.*
import ai.forge.core.model.ChatMessage
import ai.forge.core.model.Role
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.*

class OpenAiProviderTest {
    @Test fun parsesStreamingResponseFromCompatibleServer() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                """
                data: {"choices":[{"delta":{"role":"assistant"}}]}

                data: {"choices":[{"delta":{"content":"Bon"}}]}

                data: {"choices":[{"delta":{"content":"jour"},"finish_reason":"stop"}]}

                data: [DONE]

                """.trimIndent()))
            server.start()
            val p = OpenAiCompatibleProvider("llamacpp", "llama.cpp", server.url("/v1").toString(), "local-model",
                capabilities = ProviderCapabilities(Locality.LOCAL_NETWORK, 8192))
            val events = p.stream(ChatRequest(listOf(ChatMessage(Role.USER, "Salut")))).toList()
            assertEquals("Bonjour", events.filterIsInstance<LlmEvent.Token>().joinToString("") { it.text })
            assertEquals("stop", (events.last() as LlmEvent.Done).finishReason)
            val recorded = server.takeRequest()
            assertEquals("/v1/chat/completions", recorded.path)
            assertNull(recorded.getHeader("Authorization"))
            assertTrue(recorded.body.readUtf8().contains("\"stream\":true"))
        }
    }

    @Test fun reportsHttpErrors() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":\"bad key\"}"))
            server.start()
            val p = OpenAiCompatibleProvider("x", "x", server.url("/v1").toString(), "m", apiKey = "k",
                capabilities = ProviderCapabilities(Locality.CLOUD, 8192))
            val r = p.complete(ChatRequest(listOf(ChatMessage(Role.USER, "Salut"))))
            assertTrue(r.isFailure)
            assertTrue(r.exceptionOrNull()!!.message!!.contains("401"))
            assertEquals("Bearer k", server.takeRequest().getHeader("Authorization"))
        }
    }

    @Test fun anthropicStreamParsing() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(
                """
                event: content_block_delta
                data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"Salut"}}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"end_turn"}}

                """.trimIndent()))
            server.start()
            val p = AnthropicProvider("key", baseUrl = server.url("").toString().trimEnd('/'))
            val ev = p.stream(ChatRequest(listOf(ChatMessage(Role.SYSTEM, "sys"), ChatMessage(Role.USER, "hi")))).toList()
            assertEquals("Salut", (ev.first() as LlmEvent.Token).text)
            assertEquals("end_turn", (ev.last() as LlmEvent.Done).finishReason)
            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("\"system\":\"sys\""))
            assertFalse(body.contains("\"role\":\"system\""))
        }
    }
}

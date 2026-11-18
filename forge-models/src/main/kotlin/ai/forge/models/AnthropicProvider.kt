package ai.forge.models

import ai.forge.core.model.Role
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Moteur cloud facultatif : API Messages d'Anthropic (clé fournie par l'utilisateur). */
class AnthropicProvider(
    private val apiKey: String,
    private val model: String = "claude-sonnet-5-5",
    override val id: String = "anthropic",
    override val displayName: String = "Claude (cloud, facultatif)",
    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        locality = Locality.CLOUD, contextTokens = 200_000, vision = true, toolUse = true,
        reasoningQuality = 5, relativeCost = 3,
    ),
    private val baseUrl: String = "https://api.anthropic.com",
    private val client: OkHttpClient = OpenAiCompatibleProvider.defaultClient(),
) : LlmProvider {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun isAvailable(): Boolean = apiKey.isNotBlank()

    override fun stream(request: ChatRequest): Flow<LlmEvent> = flow {
        val system = request.messages.filter { it.role == Role.SYSTEM }.joinToString("\n\n") { it.content }
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", request.maxTokens)
            put("temperature", request.temperature)
            put("stream", true)
            if (system.isNotBlank()) put("system", system)
            putJsonArray("messages") {
                request.messages.filter { it.role != Role.SYSTEM }.forEach { m ->
                    addJsonObject {
                        put("role", if (m.role == Role.ASSISTANT) "assistant" else "user")
                        put("content", m.content)
                    }
                }
            }
        }
        val http = Request.Builder()
            .url("$baseUrl/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        var stop: String? = null
        client.sseLines(http).collect { line ->
            when (line) {
                is SseLine.HttpError -> emit(LlmEvent.Failure("HTTP ${line.code} : ${line.body}"))
                is SseLine.IoError -> emit(LlmEvent.Failure("Erreur réseau : ${line.error.message}", line.error))
                is SseLine.Data -> {
                    val obj = runCatching { json.parseToJsonElement(line.data).jsonObject }.getOrNull() ?: return@collect
                    when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                        "content_block_delta" -> obj["delta"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
                            ?.let { emit(LlmEvent.Token(it)) }
                        "message_delta" -> stop = obj["delta"]?.jsonObject?.get("stop_reason")?.jsonPrimitive?.contentOrNull
                        "error" -> emit(LlmEvent.Failure(obj["error"].toString()))
                    }
                }
            }
        }
        emit(LlmEvent.Done(stop))
    }
}

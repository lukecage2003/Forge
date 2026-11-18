package ai.forge.models

import ai.forge.core.model.Role
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Moteur compatible avec l'API « chat/completions » d'OpenAI.
 * Couvre de nombreux back-ends : llama.cpp server, Ollama (/v1), LM Studio, vLLM,
 * Mistral, OpenRouter, OpenAI… La clé API est facultative (serveurs locaux).
 */
class OpenAiCompatibleProvider(
    override val id: String,
    override val displayName: String,
    private val baseUrl: String,
    private val model: String,
    private val apiKey: String? = null,
    override val capabilities: ProviderCapabilities,
    private val client: OkHttpClient = defaultClient(),
) : LlmProvider {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun isAvailable(): Boolean = runCatching {
        val req = Request.Builder().url(baseUrl.trimEnd('/') + "/models").apply { auth() }.get().build()
        client.newCall(req).execute().use { it.isSuccessful }
    }.getOrDefault(false)

    override fun stream(request: ChatRequest): Flow<LlmEvent> = flow {
        val body = buildJsonObject {
            put("model", model)
            put("stream", true)
            put("temperature", request.temperature)
            put("max_tokens", request.maxTokens)
            if (request.stop.isNotEmpty()) putJsonArray("stop") { request.stop.forEach { add(it) } }
            putJsonArray("messages") {
                request.messages.forEach { m ->
                    addJsonObject {
                        put("role", when (m.role) {
                            Role.SYSTEM -> "system"; Role.USER -> "user"
                            Role.ASSISTANT -> "assistant"; Role.TOOL -> "tool"
                        })
                        put("content", m.content)
                    }
                }
            }
        }
        val http = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/chat/completions")
            .apply { auth() }
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        var finish: String? = null
        client.sseLines(http).collect { line ->
            when (line) {
                is SseLine.HttpError -> emit(LlmEvent.Failure("HTTP ${line.code} : ${line.body}"))
                is SseLine.IoError -> emit(LlmEvent.Failure("Erreur réseau : ${line.error.message}", line.error))
                is SseLine.Data -> {
                    if (line.data == "[DONE]") return@collect
                    val obj = runCatching { json.parseToJsonElement(line.data).jsonObject }.getOrNull() ?: return@collect
                    val choice = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return@collect
                    choice["delta"]?.jsonObject?.get("content")?.let { c ->
                        (c as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { emit(LlmEvent.Token(it)) }
                    }
                    choice["finish_reason"]?.jsonPrimitive?.contentOrNull?.let { finish = it }
                }
            }
        }
        emit(LlmEvent.Done(finish))
    }

    private fun Request.Builder.auth() {
        apiKey?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }
}

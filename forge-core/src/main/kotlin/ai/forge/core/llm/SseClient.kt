package ai.forge.core.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

/** Lecture ligne à ligne d'un flux Server-Sent Events, annulable. */
internal fun OkHttpClient.sseLines(request: Request): Flow<SseLine> = callbackFlow {
    val call = newCall(request)
    val job = launch(Dispatchers.IO) {
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string()?.take(500).orEmpty()
                    send(SseLine.HttpError(response.code, body))
                    return@use
                }
                val source = response.body!!.source()
                var event: String? = null
                while (isActive && !source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    when {
                        line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                        line.startsWith("data:") -> send(SseLine.Data(event, line.removePrefix("data:").trim()))
                        line.isBlank() -> event = null
                    }
                }
            }
        } catch (t: Throwable) {
            if (!call.isCanceled()) send(SseLine.IoError(t))
        } finally {
            channel.close()
        }
    }
    awaitClose { call.cancel(); job.cancel() }
}.flowOn(Dispatchers.IO)

internal sealed interface SseLine {
    data class Data(val event: String?, val data: String) : SseLine
    data class HttpError(val code: Int, val body: String) : SseLine
    data class IoError(val error: Throwable) : SseLine
}

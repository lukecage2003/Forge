package ai.forge.app.voice

import ai.forge.core.voice.SpeechOutput
import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Synthèse vocale via le moteur TTS du système (Samsung TTS ou Google Speech Services sur S25).
 * Choisit la voix française locale de meilleure qualité. Interchangeable avec d'autres moteurs
 * (Piper / Sherpa-ONNX en local, ou cloud facultatif) grâce à l'interface [SpeechOutput].
 */
class AndroidTtsOutput(context: Context) : SpeechOutput {
    private val ready = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready.complete(status == TextToSpeech.SUCCESS)
    }

    var speechRate: Float = 1.0f
        set(value) { field = value.coerceIn(0.5f, 2.0f); tts.setSpeechRate(field) }
    var pitch: Float = 0.95f
        set(value) { field = value.coerceIn(0.5f, 2.0f); tts.setPitch(field) }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit
            override fun onDone(utteranceId: String) { pending.remove(utteranceId)?.complete(Unit) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) { pending.remove(utteranceId)?.complete(Unit) }
            override fun onStop(utteranceId: String, interrupted: Boolean) { pending.remove(utteranceId)?.complete(Unit) }
        })
    }

    /** Voix disponibles en français, de la meilleure à la moins bonne (pour l'écran de réglages). */
    suspend fun frenchVoices(): List<Voice> {
        if (!ready.await()) return emptyList()
        return tts.voices.orEmpty()
            .filter { it.locale.language == Locale.FRENCH.language }
            .sortedWith(compareBy<Voice> { it.isNetworkConnectionRequired }.thenByDescending { it.quality }.thenBy { it.latency })
    }

    suspend fun selectVoice(name: String? = null) {
        val voices = frenchVoices()
        val chosen = voices.firstOrNull { it.name == name } ?: voices.firstOrNull()
        if (chosen != null) tts.voice = chosen else tts.language = Locale.FRANCE
        tts.setSpeechRate(speechRate); tts.setPitch(pitch)
    }

    override suspend fun speak(segment: String) {
        if (!ready.await()) return
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Unit>()
        pending[id] = done
        tts.speak(segment, TextToSpeech.QUEUE_ADD, Bundle(), id)
        suspendCancellableCoroutine { cont ->
            done.invokeOnCompletion { if (cont.isActive) cont.resume(Unit) }
            cont.invokeOnCancellation { stopNow() }
        }
    }

    override fun stopNow() {
        tts.stop()
        pending.values.forEach { it.complete(Unit) }
        pending.clear()
    }

    fun shutdown() { stopNow(); tts.shutdown() }
}

package ai.forge.app.voice

import ai.forge.voice.SpeechOutput
import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Synthèse vocale naturelle avec le moteur du téléphone (Google ou Samsung).
 * - File continue : la phrase suivante est préparée pendant que la précédente est dite (pas de blanc).
 * - Meilleure voix française disponible : les voix « réseau » (neuronales, très naturelles) quand
 *   Internet est là, sinon la meilleure voix locale ; choix manuel possible dans les Réglages.
 */
class AndroidTtsOutput(private val context: Context) : SpeechOutput {
    private val ready = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val order = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private val tts: TextToSpeech = createTts()

    /** Nom de la voix choisie par l'utilisateur (null = automatique). */
    @Volatile var preferredVoice: String? = null
    @Volatile var allowNetworkVoices: Boolean = true
    val activeVoice = MutableStateFlow<String?>(null)

    var speechRate: Float = 1.25f
        set(value) { field = value.coerceIn(0.5f, 2.0f); tts.setSpeechRate(field) }
    var pitch: Float = 1.0f
        set(value) { field = value.coerceIn(0.5f, 2.0f); tts.setPitch(field) }

    private fun createTts(): TextToSpeech {
        // Moteur Google en priorité s'il est installé (voix neuronales françaises de très bonne qualité).
        val google = "com.google.android.tts"
        val engine = runCatching { context.packageManager.getPackageInfo(google, 0); google }.getOrNull()
        val listener = TextToSpeech.OnInitListener { status -> ready.complete(status == TextToSpeech.SUCCESS) }
        return if (engine != null) TextToSpeech(context.applicationContext, listener, engine) else TextToSpeech(context.applicationContext, listener)
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit
            override fun onDone(utteranceId: String) = finish(utteranceId)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) = finish(utteranceId)
            override fun onError(utteranceId: String, errorCode: Int) = finish(utteranceId)
            override fun onStop(utteranceId: String, interrupted: Boolean) = finish(utteranceId)
        })
    }

    private fun finish(id: String) { order.remove(id); pending.remove(id)?.complete(Unit) }

    /** Voix françaises, de la plus naturelle à la moins naturelle. */
    suspend fun frenchVoices(): List<Voice> {
        if (!ready.await()) return emptyList()
        return tts.voices.orEmpty()
            .filter { it.locale.language == Locale.FRENCH.language && !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            .sortedWith(compareByDescending<Voice> { it.quality }.thenBy { it.locale.country != "FR" }.thenBy { it.latency })
    }

    /** Applique la voix préférée, ou la meilleure disponible (réseau autorisé si [online]). */
    suspend fun selectBestVoice(online: Boolean) {
        val voices = frenchVoices()
        val chosen = voices.firstOrNull { it.name == preferredVoice }
            ?: voices.firstOrNull { !it.isNetworkConnectionRequired || (online && allowNetworkVoices) }
        if (chosen != null) tts.voice = chosen else tts.language = Locale.FRANCE
        tts.setSpeechRate(speechRate); tts.setPitch(pitch)
        activeVoice.value = chosen?.let { describe(it) }
    }

    fun describe(v: Voice): String = buildString {
        append(v.name.substringAfter("fr-").substringAfter("fr_").replace('-', ' ').ifBlank { v.name })
        append(if (v.isNetworkConnectionRequired) " · naturelle (en ligne)" else " · hors-ligne")
        if (v.quality >= Voice.QUALITY_VERY_HIGH) append(" · très haute qualité")
    }

    /** Écoute d'essai d'une voix. */
    suspend fun preview(voiceName: String, text: String = "Bonjour. Je suis Forge. Que préparons-nous aujourd'hui ?") {
        val v = frenchVoices().firstOrNull { it.name == voiceName } ?: return
        stopNow(); tts.voice = v; speak(text); awaitIdle()
    }

    override suspend fun speak(segment: String) {
        if (!ready.await()) return
        // Contre-pression : au plus 2 phrases d'avance (interruption toujours immédiate).
        while (order.size >= 2) order.peek()?.let { pending[it]?.await() } ?: break
        val id = UUID.randomUUID().toString()
        pending[id] = CompletableDeferred(); order.add(id)
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f) }
        if (tts.speak(segment, TextToSpeech.QUEUE_ADD, params, id) != TextToSpeech.SUCCESS) finish(id)
    }

    override suspend fun awaitIdle() { pending.values.toList().awaitAll() }

    override fun stopNow() {
        tts.stop()
        pending.values.forEach { it.complete(Unit) }
        pending.clear(); order.clear()
    }

    fun shutdown() { stopNow(); tts.shutdown() }
}

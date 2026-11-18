package ai.forge.app.voice

import ai.forge.voice.WakeWordSpotter
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/**
 * Veille « Forge » : Vosk (hors-ligne, Apache 2.0) écoute une grammaire réduite au mot d'appel et
 * à quelques leurres. Peu gourmand : aucun réseau, aucun grand modèle. Quand « Forge » est entendu,
 * le micro est libéré immédiatement pour que la question soit prise par la reconnaissance complète.
 */
class VoskWakeWord(private val modelDir: String, private val spotter: WakeWordSpotter, private val onWake: () -> Unit, private val onError: (String) -> Unit) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var model: Model? = null
    @Volatile private var service: SpeechService? = null
    @Volatile var running = false; private set

    fun start() {
        if (running) return
        running = true
        scope.launch {
            runCatching {
                val m = model ?: Model(modelDir).also { model = it }
                val rec = Recognizer(m, 16_000f, WakeWordSpotter.GRAMMAR).apply { setWords(true) }
                val s = SpeechService(rec, 16_000f)
                service = s
                s.startListening(object : RecognitionListener {
                    override fun onPartialResult(hypothesis: String?) { if (hypothesis != null && spotter.onPartial(hypothesis)) fire() }
                    override fun onResult(hypothesis: String?) { if (hypothesis != null && spotter.onResult(hypothesis)) fire() }
                    override fun onFinalResult(hypothesis: String?) = Unit
                    override fun onError(exception: Exception?) { main.post { onError("Veille « Forge » : ${exception?.message}") } }
                    override fun onTimeout() = Unit
                })
            }.onFailure { running = false; main.post { onError("Veille « Forge » indisponible : ${it.message}") } }
        }
    }

    private fun fire() { stop(); main.post(onWake) }

    fun stop() {
        running = false
        service?.let { runCatching { it.stop(); it.shutdown() } }
        service = null
    }

    fun release() { stop(); runCatching { model?.close() }; model = null }
}

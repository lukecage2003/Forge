package ai.forge.app.voice

import ai.forge.voice.Endpointer
import ai.forge.voice.RecognitionPolicy
import ai.forge.voice.UtteranceDeduplicator
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Reconnaissance vocale robuste (corrige les erreurs en boucle et les doublons) :
 * - erreurs bénignes (silence, fin de session, serveur relancé) traitées en silence, avec délai croissant ;
 * - moteur recréé si son état se corrompt ; bascule hors-ligne → standard si le pack français manque ;
 * - un énoncé n'est transmis qu'une fois (fin détectée par Forge OU par Android, jamais les deux) ;
 * - [pause]/[resume] : le micro se coupe pendant que Forge parle (pas d'écho capté).
 */
class AndroidSpeechInput(
    private val context: Context,
    private val endpointer: Endpointer = Endpointer(),
    private val onPartial: (String) -> Unit,
    private val onSpeechStart: () -> Unit,
    private val onFinal: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onLevel: (Float) -> Unit = {},
) {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var active = false
    private var paused = false
    private var listening = false
    private var useOnDevice = true
    private var consecutiveErrors = 0
    private var finalizedThisSession = false
    private var lastPartial = ""
    private var lastChangeAt = 0L
    private val dedup = UtteranceDeduplicator()

    private val endCheck = object : Runnable {
        override fun run() {
            if (!active || paused || lastPartial.isBlank() || finalizedThisSession) return
            val silence = System.currentTimeMillis() - lastChangeAt
            if (endpointer.isEndOfTurn(lastPartial, silence)) {
                deliver(lastPartial)
                recognizer?.stopListening()
            } else main.postDelayed(this, 120)
        }
    }
    private val relisten = Runnable { listen() }

    fun start() = main.post {
        if (active) return@post
        active = true; paused = false; consecutiveErrors = 0
        createRecognizer(); listen()
    }

    fun stop() = main.post {
        active = false; listening = false
        main.removeCallbacks(endCheck); main.removeCallbacks(relisten)
        recognizer?.cancel(); recognizer?.destroy(); recognizer = null
        lastPartial = ""
    }

    /** Coupe l'écoute (Forge parle) sans fermer la session. */
    fun pause() = main.post {
        if (!active || paused) return@post
        paused = true; listening = false
        main.removeCallbacks(endCheck); main.removeCallbacks(relisten)
        recognizer?.cancel()
        lastPartial = ""
    }

    fun resume() = main.post {
        if (!active || !paused) return@post
        paused = false
        main.removeCallbacks(relisten); main.postDelayed(relisten, 250)
    }

    private fun createRecognizer() {
        recognizer?.destroy()
        recognizer = (if (useOnDevice && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else { useOnDevice = false; SpeechRecognizer.createSpeechRecognizer(context) }).apply { setRecognitionListener(listener) }
    }

    private fun listen() {
        if (!active || paused || listening) return
        finalizedThisSession = false; lastPartial = ""
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1_500L)
        }
        runCatching { recognizer?.startListening(intent); listening = true }.onFailure { scheduleRetry(600) }
    }

    private fun scheduleRetry(delayMs: Long) { listening = false; main.removeCallbacks(relisten); main.postDelayed(relisten, delayMs) }

    private fun deliver(text: String) {
        finalizedThisSession = true; lastPartial = ""
        main.removeCallbacks(endCheck)
        if (dedup.accept(text)) onFinal(text)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() { onSpeechStart() }
        override fun onRmsChanged(rmsdB: Float) { onLevel(rmsdB) }
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(partialResults: Bundle) {
            if (paused || finalizedThisSession) return
            val text = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (text.isNotBlank() && text != lastPartial) {
                lastPartial = text; lastChangeAt = System.currentTimeMillis()
                onPartial(text)
                main.removeCallbacks(endCheck); main.postDelayed(endCheck, 120)
            }
        }

        override fun onResults(results: Bundle) {
            listening = false; consecutiveErrors = 0
            val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().ifBlank { lastPartial }
            if (!paused && !finalizedThisSession && text.isNotBlank()) deliver(text)
            scheduleRetry(120)
        }

        override fun onError(error: Int) {
            listening = false
            if (paused || !active) return                       // erreur provoquée par notre propre pause/arrêt
            if (error != RecognitionPolicy.NO_MATCH && error != RecognitionPolicy.SPEECH_TIMEOUT) consecutiveErrors++
            when (val a = RecognitionPolicy.decide(error, consecutiveErrors, useOnDevice)) {
                is RecognitionPolicy.Action.Retry -> scheduleRetry(a.delayMs)
                is RecognitionPolicy.Action.Recreate -> { createRecognizer(); scheduleRetry(a.delayMs) }
                RecognitionPolicy.Action.SwitchToStandardEngine -> { useOnDevice = false; createRecognizer(); scheduleRetry(300) }
                is RecognitionPolicy.Action.Stop -> { onError(a.message); active = false; recognizer?.destroy(); recognizer = null }
            }
        }
    }
}

package ai.forge.app.voice

import ai.forge.voice.Endpointer
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Reconnaissance vocale via le service du système, en privilégiant la reconnaissance
 * sur l'appareil (Android 13+ : createOnDeviceSpeechRecognizer quand disponible).
 *
 * Limites connues (documentées dans docs/STATUS.md) :
 * - SpeechRecognizer fonctionne par sessions : on le relance après chaque énoncé (écoute continue).
 * - Pendant que Forge parle, le micro peut capter sa propre voix : l'interruption vocale est
 *   fiable surtout avec écouteurs ; sinon, le bouton « Interrompre » est toujours disponible.
 * - L'endpointing natif est complété par [Endpointer] (hésitations du français).
 */
class AndroidSpeechInput(
    private val context: Context,
    private val endpointer: Endpointer = Endpointer(),
    private val onPartial: (String) -> Unit,
    private val onSpeechStart: () -> Unit,
    private val onFinal: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var active = false
    private var lastPartial = ""
    private var lastChangeAt = 0L
    private val endCheck = object : Runnable {
        override fun run() {
            if (!active || lastPartial.isBlank()) return
            val silence = System.currentTimeMillis() - lastChangeAt
            if (endpointer.isEndOfTurn(lastPartial, silence)) {
                val text = lastPartial; lastPartial = ""
                recognizer?.stopListening()
                onFinal(text)
            } else main.postDelayed(this, 120)
        }
    }

    fun start() {
        if (active) return
        active = true
        recognizer = createRecognizer().apply { setRecognitionListener(listener) }
        listen()
    }

    fun stop() {
        active = false
        main.removeCallbacks(endCheck)
        recognizer?.cancel(); recognizer?.destroy(); recognizer = null
        lastPartial = ""
    }

    private fun createRecognizer(): SpeechRecognizer =
        if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else SpeechRecognizer.createSpeechRecognizer(context)

    private fun listen() {
        if (!active) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1_500L)
        }
        recognizer?.startListening(intent)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() { onSpeechStart() }
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(partialResults: Bundle) {
            val text = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (text.isNotBlank() && text != lastPartial) {
                lastPartial = text; lastChangeAt = System.currentTimeMillis()
                onPartial(text)
                main.removeCallbacks(endCheck); main.postDelayed(endCheck, 120)
            }
        }

        override fun onResults(results: Bundle) {
            main.removeCallbacks(endCheck)
            val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            val final = text.ifBlank { lastPartial }
            lastPartial = ""
            if (final.isNotBlank()) onFinal(final)
            main.postDelayed({ listen() }, 150)
        }

        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> main.postDelayed({ listen() }, 200)
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> main.postDelayed({ listen() }, 500)
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { onError("Permission micro refusée."); stop() }
                else -> { onError("Reconnaissance vocale : erreur $error"); main.postDelayed({ listen() }, 800) }
            }
        }
    }
}

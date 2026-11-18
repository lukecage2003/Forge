package ai.forge.app.voice

import ai.forge.voice.SpeechOutput
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.google.mediapipe.tasks.audio.texttospeech.SynthesisOptions
import com.google.mediapipe.tasks.audio.texttospeech.TextToSpeech
import com.google.mediapipe.tasks.audio.texttospeech.TextToSpeechOptions
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Voix neuronale 100 % hors-ligne : Kyutai Pocket TTS (voix française « Estelle », CC-BY 4.0)
 * via la tâche MediaPipe TextToSpeech. Synthèse en streaming (premier son en ~50 ms),
 * lecture continue sans blanc entre les phrases, interruption immédiate.
 */
class NeuralTtsOutput(private val modelPath: String, private val cacheDir: String, rate: Float) : SpeechOutput {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var queue = Channel<Pair<String, CompletableDeferred<Unit>>>(capacity = 2)
    private var worker: Job? = null
    @Volatile private var pendingCount = 0
    private var idle = CompletableDeferred<Unit>().also { it.complete(Unit) }

    /** Débit : la voix neuronale est naturellement plus vive que la voix Android. */
    @Volatile var speakingRate: Float = (rate / 1.15f).coerceIn(0.7f, 2.0f)

    private val tts: TextToSpeech by lazy {
        TextToSpeech.create(TextToSpeechOptions(
            baseOptions = BaseOptions.builder().setModelAssetPath(modelPath).setDelegate(Delegate.CPU).build(),
            defaultVoiceId = "estelle",
            speakingRate = speakingRate,
            cacheDirectory = cacheDir,
        ))
    }

    private val track: AudioTrack by lazy {
        val rateHz = 24_000
        val min = AudioTrack.getMinBufferSize(rateHz, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rateHz).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(min, rateHz * 4 / 2)).setTransferMode(AudioTrack.MODE_STREAM).build().also { it.play() }
    }

    /** Charge le modèle à l'avance (évite la latence de la première phrase). */
    fun warmUp() = scope.launch { runCatching { tts.listVoices(); track } }

    private fun startWorker() {
        worker = scope.launch {
            for ((text, done) in queue) {
                runCatching {
                    if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
                    tts.synthesizeStream(text, SynthesisOptions(voiceId = "estelle", locale = "fr-FR")).collect { chunk ->
                        track.write(chunk.samples, 0, chunk.samples.size, AudioTrack.WRITE_BLOCKING)
                    }
                }
                done.complete(Unit)
                synchronized(this@NeuralTtsOutput) { pendingCount--; if (pendingCount == 0) idle.complete(Unit) }
            }
        }
    }

    override suspend fun speak(segment: String) {
        if (worker == null) startWorker()
        synchronized(this) { if (pendingCount == 0) idle = CompletableDeferred(); pendingCount++ }
        queue.send(segment to CompletableDeferred())
    }

    override suspend fun awaitIdle() {
        idle.await()
        // Laisse la fin du tampon audio se jouer avant de rendre le micro.
        delay(250)
    }

    override fun stopNow() {
        runCatching { tts.cancel() }
        worker?.cancel(); worker = null
        queue.cancel(); queue = Channel(capacity = 2)
        runCatching { track.pause(); track.flush() }
        synchronized(this) { pendingCount = 0; idle.complete(Unit) }
    }

    fun close() { stopNow(); runCatching { tts.close() }; runCatching { track.release() } }
}

/** Choisit à chaque phrase la voix active (neuronale si installée et activée, sinon Android). */
class SwitchableSpeechOutput(private val current: () -> SpeechOutput) : SpeechOutput {
    private var last: SpeechOutput? = null
    override suspend fun speak(segment: String) { val o = current(); last = o; o.speak(segment) }
    override suspend fun awaitIdle() { (last ?: current()).awaitIdle() }
    override fun stopNow() { (last ?: current()).stopNow() }
}

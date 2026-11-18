package ai.forge.app.voice

import ai.forge.voice.SpeechSegmenter
import ai.forge.voice.UtteranceDeduplicator
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Moteur Parakeet chargé une seule fois (plusieurs centaines de Mo) et partagé. */
object ParakeetEngine {
    @Volatile private var loaded: Pair<String, OfflineRecognizer>? = null

    @Synchronized fun get(dir: File): OfflineRecognizer {
        loaded?.takeIf { it.first == dir.absolutePath }?.let { return it.second }
        loaded?.second?.release(); loaded = null
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = File(dir, "encoder.int8.onnx").absolutePath,
                    decoder = File(dir, "decoder.int8.onnx").absolutePath,
                    joiner = File(dir, "joiner.int8.onnx").absolutePath,
                ),
                tokens = File(dir, "tokens.txt").absolutePath,
                numThreads = 2,                    // 2 cœurs : le reste du téléphone reste fluide
                modelType = "nemo_transducer",
            ),
        )
        return OfflineRecognizer(null, config).also { loaded = dir.absolutePath to it }
    }

    fun isInstalled(dir: File?) = dir != null && listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt").all { File(dir, it).isFile }

    @Synchronized fun release() { loaded?.second?.release(); loaded = null }
}

/**
 * Reconnaissance vocale hors-ligne Parakeet : le micro est lu directement, la parole est découpée
 * par [SpeechSegmenter], chaque segment est transcrit sur le téléphone (rien ne quitte l'appareil).
 * Mêmes rappels que la reconnaissance Android, pour être interchangeable.
 */
class ParakeetSpeechInput(
    private val modelDir: File,
    private val onPartial: (String) -> Unit,
    private val onSpeechStart: () -> Unit,
    private val onFinal: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onLevel: (Float) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val decoder = Executors.newSingleThreadExecutor { r -> Thread(r, "forge-parakeet").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val interimBusy = AtomicBoolean(false)
    private val dedup = UtteranceDeduplicator()
    @Volatile private var running = false
    @Volatile private var paused = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true; paused = false
        thread = Thread({ loop() }, "forge-micro").apply { start() }
    }

    fun stop() { running = false; thread?.join(600); thread = null }
    fun pause() { paused = true }
    fun resume() { paused = false }

    /** Précharge le moteur (au démarrage de la veille), pour que la première phrase soit rapide. */
    fun warmUp() { decoder.execute { runCatching { ParakeetEngine.get(modelDir) } } }

    private fun decode(audio: FloatArray): String {
        val r = ParakeetEngine.get(modelDir)
        val stream = r.createStream()
        return try { stream.acceptWaveform(audio, 16_000); r.decode(stream); r.getResult(stream).text.trim() } finally { stream.release() }
    }

    @SuppressLint("MissingPermission")
    private fun loop() {
        val rate = 16_000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching { AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate)) }
            .getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        if (rec == null) { running = false; main.post { onError("Micro indisponible pour la reconnaissance hors-ligne.") }; return }
        val ns = if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true } }.getOrNull() else null
        val aec = if (AcousticEchoCanceler.isAvailable()) runCatching { AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true } }.getOrNull() else null
        val seg = SpeechSegmenter(interimEveryMs = 2_000)
        val shorts = ShortArray(320)                               // 20 ms
        var lastLevelAt = 0L
        try {
            rec.startRecording()
            while (running) {
                val n = rec.read(shorts, 0, shorts.size)
                if (n <= 0) continue
                if (paused) { seg.reset(); continue }
                val frame = FloatArray(n) { shorts[it] / 32768f }
                val events = seg.push(frame)
                val now = System.currentTimeMillis()
                if (now - lastLevelAt > 50) { lastLevelAt = now; val lvl = ((seg.lastDbfs + 60) / 4).toFloat().coerceIn(-2f, 10f); main.post { onLevel(lvl) } }
                for (e in events) when (e) {
                    is SpeechSegmenter.Event.SpeechStart -> main.post { onSpeechStart() }
                    is SpeechSegmenter.Event.Interim -> if (interimBusy.compareAndSet(false, true)) decoder.execute {
                        // Aperçu en direct : seulement les 6 dernières secondes (coût faible et constant).
                        val tail = if (e.audio.size > 96_000) e.audio.copyOfRange(e.audio.size - 96_000, e.audio.size) else e.audio
                        runCatching { decode(tail) }.onSuccess { t -> seg.lastInterim = t; if (t.isNotBlank()) main.post { onPartial(t) } }
                        interimBusy.set(false)
                    }
                    is SpeechSegmenter.Event.End -> decoder.execute {
                        runCatching { decode(e.audio) }
                            .onSuccess { t -> if (t.isNotBlank() && dedup.accept(t)) main.post { onFinal(t) } }
                            .onFailure { main.post { onError("Reconnaissance hors-ligne : ${it.message}") } }
                    }
                }
            }
        } catch (t: Throwable) {
            main.post { onError("Micro : ${t.message}") }
        } finally {
            runCatching { rec.stop() }; rec.release(); ns?.release(); aec?.release()
        }
    }
}

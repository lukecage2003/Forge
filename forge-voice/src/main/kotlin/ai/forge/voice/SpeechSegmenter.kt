package ai.forge.voice

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Découpe un flux audio (PCM 16 kHz mono) en tours de parole, sans modèle : détection d'énergie
 * avec plancher de bruit adaptatif. Sert au moteur de reconnaissance hors-ligne (Parakeet), qui
 * transcrit des segments complets plutôt qu'un flux.
 *
 * - début de parole : énergie au-dessus du bruit ambiant pendant [startMs] ;
 * - transcription provisoire toutes les [interimEveryMs] (texte affiché en direct) ;
 * - fin : silence assez long, calculé par l'[Endpointer] à partir du dernier texte provisoire
 *   (plus long après « euh », « et », une virgule…) ; coupure forcée après [maxUtteranceMs].
 */
class SpeechSegmenter(
    private val sampleRate: Int = 16_000,
    private val endpointer: Endpointer = Endpointer(),
    private val startMs: Long = 120,
    private val interimEveryMs: Long = 1_200,
    private val maxUtteranceMs: Long = 30_000,
    private val preRollMs: Long = 300,
    private val ratio: Double = 3.2,
    private val minDbfs: Double = -52.0,
) {
    sealed interface Event {
        data object SpeechStart : Event
        class Interim(val audio: FloatArray) : Event
        class End(val audio: FloatArray) : Event
    }

    private var noise = -60.0
    private var speaking = false
    private var loudMs = 0L
    private var silenceMs = 0L
    private var spokenMs = 0L
    private var sinceInterim = 0L
    private val utterance = FloatArrayBuilder()
    private val preRoll = ArrayDeque<FloatArray>()
    private var preRollLen = 0L
    /** Dernier texte provisoire connu (fourni par le moteur), pour juger si la phrase est finie. */
    @Volatile var lastInterim: String = ""

    /** Niveau du dernier bloc, en dBFS (pour l'animation de la voix). */
    var lastDbfs: Double = -90.0
        private set

    fun reset() { speaking = false; loudMs = 0; silenceMs = 0; spokenMs = 0; sinceInterim = 0; utterance.clear(); preRoll.clear(); preRollLen = 0; lastInterim = "" }

    fun push(frame: FloatArray): List<Event> {
        val ms = frame.size * 1000L / sampleRate
        val db = dbfs(frame); lastDbfs = db
        val loud = db > max(minDbfs, noise + 20 * log10(ratio))
        if (!loud) noise = if (db < noise) db * 0.3 + noise * 0.7 else db * 0.02 + noise * 0.98
        val out = mutableListOf<Event>()
        if (!speaking) {
            preRoll.addLast(frame); preRollLen += ms
            while (preRollLen > preRollMs && preRoll.size > 1) preRollLen -= preRoll.removeFirst().size * 1000L / sampleRate
            loudMs = if (loud) loudMs + ms else 0
            if (loudMs >= startMs) {
                speaking = true; silenceMs = 0; spokenMs = 0; sinceInterim = 0; lastInterim = ""
                preRoll.forEach { utterance.add(it) }; preRoll.clear(); preRollLen = 0
                out += Event.SpeechStart
            }
            return out
        }
        utterance.add(frame); spokenMs += ms; sinceInterim += ms
        silenceMs = if (loud) 0 else silenceMs + ms
        val needed = if (lastInterim.isBlank()) 1_100 else endpointer.requiredSilenceMs(lastInterim)
        if (silenceMs >= needed || spokenMs >= maxUtteranceMs) {
            out += Event.End(utterance.toArray()); speaking = false; utterance.clear(); loudMs = 0
        } else if (sinceInterim >= interimEveryMs && silenceMs < 400) {
            sinceInterim = 0; out += Event.Interim(utterance.toArray())
        }
        return out
    }

    private fun dbfs(f: FloatArray): Double {
        if (f.isEmpty()) return -90.0
        var s = 0.0; for (x in f) s += x * x
        val rms = sqrt(s / f.size)
        return if (rms <= 1e-9) -90.0 else 20 * log10(rms)
    }

    private class FloatArrayBuilder {
        private var data = FloatArray(16_000 * 4); private var n = 0
        fun add(f: FloatArray) { if (n + f.size > data.size) data = data.copyOf(max(data.size * 2, n + f.size)); f.copyInto(data, n); n += f.size }
        fun toArray() = data.copyOf(n)
        fun clear() { n = 0 }
    }
}

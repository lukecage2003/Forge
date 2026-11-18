package ai.forge.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** États visibles en permanence par l'utilisateur (§2.6 : indicateurs d'écoute, réflexion, réponse). */
enum class VoiceState { IDLE, LISTENING, THINKING, SPEAKING }

/** Abstraction de la synthèse vocale (Android TextToSpeech, Piper/Sherpa-ONNX, cloud…). */
interface SpeechOutput {
    /**
     * Met un segment en file d'attente. Une implémentation « fluide » peut rendre la main dès que le
     * segment est accepté (la phrase suivante se prépare pendant que la précédente est dite : pas de
     * silence mécanique entre les phrases) ; [awaitIdle] attend alors la fin réelle de la parole.
     */
    suspend fun speak(segment: String)
    /** Attend que tout ce qui a été mis en file soit prononcé (ou annulé). */
    suspend fun awaitIdle() {}
    /** Arrêt immédiat de toute parole en cours et vidage de la file. */
    fun stopNow()
}

/** Ce que la session demande au cerveau : un flux de texte annulable. */
fun interface ResponseSource {
    fun respond(utterance: String): Flow<String>
}

/**
 * Pilote d'une session vocale full-duplex logique (§2.2) :
 * - l'utilisateur parle → l'endpointer décide de la fin du tour ;
 * - Forge réfléchit puis parle par segments (streaming) ;
 * - toute prise de parole pendant SPEAKING est classée (stop, changement de sujet, nouvelle question,
 *   simple acquiescement) et l'annulation est immédiate.
 *
 * La capture audio elle-même (micro, VAD, reconnaissance) est fournie par la plateforme, qui appelle
 * [onFinalUtterance] et [onUserSpeechStarted]. Le micro n'est jamais ouvert par cette classe.
 */
class VoiceSessionController(
    private val scope: CoroutineScope,
    private val output: SpeechOutput,
    private val source: ResponseSource,
    private val bargeIn: BargeInClassifier = BargeInClassifier(),
    private val onInterruptedPartial: (String) -> Unit = {},
    private val onSessionEnd: () -> Unit = {},
    private val mergeWindowMs: Long = 20_000,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(VoiceState.IDLE)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private var responseJob: Job? = null
    /** Dernier énoncé traité et moment où il a été reçu (pour recoller une phrase coupée). */
    private var lastUtterance: String? = null
    private var lastUtteranceAt = 0L
    private var spokeSinceLast = false
    private val spoken = StringBuilder()

    fun start() { _state.value = VoiceState.LISTENING }

    /** Arrêt d'urgence : coupe tout, immédiatement. */
    fun emergencyStop() {
        cancelResponse(recordPartial = true)
        _state.value = VoiceState.IDLE
        onSessionEnd()
    }

    /** La plateforme détecte de la voix pendant que Forge parle : on baisse le rideau tout de suite. */
    fun onUserSpeechStarted() {
        if (_state.value == VoiceState.SPEAKING) output.stopNow()
    }

    /** Énoncé finalisé par l'endpointer. */
    fun onFinalUtterance(text: String) {
        val busy = _state.value == VoiceState.SPEAKING || _state.value == VoiceState.THINKING
        when (val b = bargeIn.classify(text)) {
            BargeIn.EndSession -> { emergencyStop(); return }
            BargeIn.StopAndListen -> { cancelResponse(recordPartial = true); _state.value = VoiceState.LISTENING; return }
            BargeIn.Backchannel -> if (busy) return
            is BargeIn.ChangeTopic -> {
                cancelResponse(recordPartial = true)
                if (b.remainder.isBlank()) { _state.value = VoiceState.LISTENING; return }
                launchResponse("Changeons de sujet. ${b.remainder}")
                return
            }
            is BargeIn.NewUtterance -> Unit
        }
        // Pause au milieu d'une phrase : si Forge n'a pas encore commencé à répondre, la suite est
        // recollée à ce qui précède au lieu de remplacer la question.
        val previous = lastUtterance
        val continuation = previous != null && !spokeSinceLast && _state.value == VoiceState.THINKING && now() - lastUtteranceAt < mergeWindowMs
        cancelResponse(recordPartial = !continuation)
        launchResponse(if (continuation) "$previous $text" else text)
    }

    private fun launchResponse(utterance: String) {
        lastUtterance = utterance; lastUtteranceAt = now(); spokeSinceLast = false
        _state.value = VoiceState.THINKING
        spoken.clear()
        responseJob = scope.launch {
            val chunker = SpeechChunker()
            source.respond(utterance).collect { tokens ->
                for (seg in chunker.push(tokens)) speakSegment(seg)
            }
            chunker.flush()?.let { speakSegment(it) }
            output.awaitIdle()
            _state.value = VoiceState.LISTENING
        }
    }

    private suspend fun speakSegment(seg: String) {
        spokeSinceLast = true
        _state.value = VoiceState.SPEAKING
        output.speak(seg)
        spoken.append(seg).append(' ')
    }

    private fun cancelResponse(recordPartial: Boolean) {
        val job = responseJob ?: return
        if (job.isActive) {
            job.cancel()
            output.stopNow()
            if (recordPartial) onInterruptedPartial(spoken.toString().trim())
        }
        responseJob = null
    }
}

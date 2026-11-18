package ai.forge.app.voice

import ai.forge.reasoning.ConversationContext
import ai.forge.reasoning.Orchestrator
import ai.forge.reasoning.OrchestratorEvent
import ai.forge.voice.ResponseSource
import ai.forge.voice.VoiceSessionController
import ai.forge.voice.VoiceState
import ai.forge.voice.DuplexPolicy
import ai.forge.voice.Vocabulary
import ai.forge.voice.WakeWord
import android.media.AudioDeviceInfo
import android.media.AudioManager
import ai.forge.voice.PresenceReducer
import ai.forge.voice.PresenceSignal
import ai.forge.voice.PresenceState
import kotlinx.coroutines.launch
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map

/** Relie micro Android, synthèse vocale et cerveau (orchestrateur). */
class VoiceCoordinator(
    private val context: Context,
    private val scope: CoroutineScope,
    orchestrator: () -> Orchestrator,
    private val conversation: () -> ConversationContext,
) {
    val tts = AndroidTtsOutput(context)
    /** Voix neuronale hors-ligne (null = non installée ou désactivée). */
    @Volatile var neural: NeuralTtsOutput? = null
    private val output = SwitchableSpeechOutput { neural ?: tts }
    val partialTranscript = MutableStateFlow("")
    val lastError = MutableStateFlow<String?>(null)
    val transcript = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    /** Niveau sonore 0..1 pour animer l'orbe (§12.1). */
    val audioLevel = MutableStateFlow(0f)
    val presence = MutableStateFlow(PresenceState.STANDBY)
    /** Réponse de Forge en cours, au fil de la génération (affichée dans la bulle). */
    val responseText = MutableStateFlow("")
    /** Dernière activité (parole de l'utilisateur ou de Forge), pour clore un tour de veille. */
    @Volatile private var lastActivity = 0L
    private fun touch() { lastActivity = System.currentTimeMillis() }
    fun signal(s: PresenceSignal) { presence.value = PresenceReducer.reduce(presence.value, s) }

    /** Vocabulaire comics + termes appris (persistés). */
    private val prefs = context.getSharedPreferences("forge_vocabulary", Context.MODE_PRIVATE)
    val vocabulary = Vocabulary(prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap())
    fun learnWord(heard: String, correct: String) { vocabulary.learn(heard, correct); prefs.edit().putString(heard, correct).apply() }

    /** Mode veille : ne répondre que si on dit « Forge… ». */
    @Volatile var requireWakeWord: Boolean = false
    @Volatile var bargeInEnabled: Boolean = false

    private fun headsetConnected(): Boolean = context.getSystemService(AudioManager::class.java)
        .getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLE_HEADSET) }

    private val controller: VoiceSessionController = VoiceSessionController(
        scope = scope,
        output = output,
        source = ResponseSource { utterance ->
            responseText.value = ""
            orchestrator().handle(utterance, conversation(), voice = true)
                .filterIsInstance<OrchestratorEvent.Token>()
                .map { touch(); responseText.value = responseText.value + it.text; it.text }
        },
        onInterruptedPartial = { conversation().addInterruptedAssistant(it) },
        onSessionEnd = { input.stop() },
    )
    val state: StateFlow<VoiceState> = controller.state

    /** Dossier du modèle Parakeet s'il est installé et activé (sinon : reconnaissance Android). */
    @Volatile var parakeetDir: () -> java.io.File? = { null }

    private val onPartialCb: (String) -> Unit = { partialTranscript.value = it; touch(); signal(PresenceSignal.PartialTranscript) }
    private val onSpeechStartCb: () -> Unit = { controller.onUserSpeechStarted() }
    private val onFinalCb: (String) -> Unit = { raw ->
        partialTranscript.value = ""; touch()
        val corrected = vocabulary.correct(raw)
        val text = if (requireWakeWord) WakeWord.strip(corrected) else corrected
        if (text != null) {
            transcript.value = transcript.value + ("Vous" to text)
            controller.onFinalUtterance(text.ifBlank { "Oui ?" })
        }
    }
    private val onErrorCb: (String) -> Unit = { lastError.value = it; signal(PresenceSignal.Failure(it)) }
    private val onLevelCb: (Float) -> Unit = { audioLevel.value = PresenceReducer.audioLevel(it) }

    private val androidInput: AndroidSpeechInput = AndroidSpeechInput(
        context, onPartial = onPartialCb, onSpeechStart = onSpeechStartCb, onFinal = onFinalCb, onError = onErrorCb, onLevel = onLevelCb,
    )
    private var parakeet: ParakeetSpeechInput? = null

    /** Choisit le moteur au démarrage de chaque session : Parakeet s'il est installé, sinon Android. */
    private inner class InputSwitch {
        private var active: Any? = null
        fun start() {
            val dir = parakeetDir()?.takeIf { ParakeetEngine.isInstalled(it) }
            active = if (dir != null) (parakeet?.takeIf { true } ?: ParakeetSpeechInput(dir, onPartialCb, onSpeechStartCb, onFinalCb, onErrorCb, onLevelCb)
                .also { parakeet = it }).also { it.start() }
            else androidInput.also { it.start() }
        }
        fun stop() { when (val a = active) { is ParakeetSpeechInput -> a.stop(); is AndroidSpeechInput -> a.stop() }; active = null }
        fun pause() { when (val a = active) { is ParakeetSpeechInput -> a.pause(); is AndroidSpeechInput -> a.pause() } }
        fun resume() { when (val a = active) { is ParakeetSpeechInput -> a.resume(); is AndroidSpeechInput -> a.resume() } }
    }
    private val input = InputSwitch()

    /** Précharge la reconnaissance hors-ligne (appelé au démarrage de la veille). */
    fun warmUpRecognizer() {
        val dir = parakeetDir()?.takeIf { ParakeetEngine.isInstalled(it) } ?: return
        (parakeet ?: ParakeetSpeechInput(dir, onPartialCb, onSpeechStartCb, onFinalCb, onErrorCb, onLevelCb).also { parakeet = it }).warmUp()
    }

    init {
        scope.launch {
            controller.state.collect { st ->
                signal(PresenceSignal.Voice(st)); touch()
                // Semi-duplex : micro coupé pendant que Forge parle (sauf écouteurs), sinon il s'entend lui-même.
                when (st) {
                    VoiceState.SPEAKING -> if (!DuplexPolicy.listenWhileSpeaking(headsetConnected(), bargeInEnabled)) input.pause()
                    VoiceState.LISTENING -> input.resume()
                    else -> Unit
                }
            }
        }
    }

    /** handsFree : session pilotée par le service de premier plan (notification permanente). */
    fun start(@Suppress("UNUSED_PARAMETER") handsFree: Boolean) { lastError.value = null; controller.start(); input.start() }
    fun stop() { turnWatch?.cancel(); controller.emergencyStop(); input.stop() }

    private var turnWatch: kotlinx.coroutines.Job? = null

    /**
     * Tour de parole déclenché par « Forge » : on écoute la question, Forge répond, et le tour se
     * clôt seul après [idleMs] de silence (on peut enchaîner plusieurs questions sans redire « Forge »).
     */
    fun startTurn(idleMs: Long = 7_000, firstSpeechMs: Long = 9_000, onEnded: () -> Unit) {
        turnWatch?.cancel()
        lastError.value = null; responseText.value = ""; partialTranscript.value = ""
        val startedAt = System.currentTimeMillis(); lastActivity = startedAt
        controller.start(); input.start()
        turnWatch = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(400)
                val quiet = System.currentTimeMillis() - lastActivity
                val nothingYet = lastActivity == startedAt
                if (state.value == VoiceState.LISTENING && partialTranscript.value.isBlank() && quiet > (if (nothingYet) firstSpeechMs else idleMs)) break
            }
            input.stop(); controller.emergencyStop()
            onEnded()
        }
    }
    /** Bouton « Interrompre » : équivalent de « attends ». */
    fun interrupt() { controller.onFinalUtterance("attends"); input.resume() }
}

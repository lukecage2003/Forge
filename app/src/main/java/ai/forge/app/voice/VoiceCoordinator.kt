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
    val partialTranscript = MutableStateFlow("")
    val lastError = MutableStateFlow<String?>(null)
    val transcript = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    /** Niveau sonore 0..1 pour animer l'orbe (§12.1). */
    val audioLevel = MutableStateFlow(0f)
    val presence = MutableStateFlow(PresenceState.STANDBY)
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
        output = tts,
        source = ResponseSource { utterance ->
            orchestrator().handle(utterance, conversation(), voice = true)
                .filterIsInstance<OrchestratorEvent.Token>()
                .map { it.text }
        },
        onInterruptedPartial = { conversation().addInterruptedAssistant(it) },
        onSessionEnd = { input.stop() },
    )
    val state: StateFlow<VoiceState> = controller.state

    private val input: AndroidSpeechInput = AndroidSpeechInput(
        context,
        onPartial = { partialTranscript.value = it; signal(PresenceSignal.PartialTranscript) },
        onSpeechStart = { controller.onUserSpeechStarted() },
        onFinal = { raw ->
            partialTranscript.value = ""
            val corrected = vocabulary.correct(raw)
            val text = if (requireWakeWord) WakeWord.strip(corrected) else corrected
            if (text != null) {
                transcript.value = transcript.value + ("Vous" to text)
                controller.onFinalUtterance(text.ifBlank { "Oui ?" })
            }
        },
        onError = { lastError.value = it; signal(PresenceSignal.Failure(it)) },
        onLevel = { audioLevel.value = PresenceReducer.audioLevel(it) },
    )

    init {
        scope.launch {
            controller.state.collect { st ->
                signal(PresenceSignal.Voice(st))
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
    fun stop() { controller.emergencyStop(); input.stop() }
    /** Bouton « Interrompre » : équivalent de « attends ». */
    fun interrupt() { controller.onFinalUtterance("attends"); input.resume() }
}

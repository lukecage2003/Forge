package ai.forge.app.voice

import ai.forge.reasoning.ConversationContext
import ai.forge.reasoning.Orchestrator
import ai.forge.reasoning.OrchestratorEvent
import ai.forge.voice.ResponseSource
import ai.forge.voice.VoiceSessionController
import ai.forge.voice.VoiceState
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map

/** Relie micro Android, synthèse vocale et cerveau (orchestrateur). */
class VoiceCoordinator(
    context: Context,
    scope: CoroutineScope,
    orchestrator: () -> Orchestrator,
    private val conversation: () -> ConversationContext,
) {
    val tts = AndroidTtsOutput(context)
    val partialTranscript = MutableStateFlow("")
    val lastError = MutableStateFlow<String?>(null)
    val transcript = MutableStateFlow<List<Pair<String, String>>>(emptyList())

    private val controller = VoiceSessionController(
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

    private val input = AndroidSpeechInput(
        context,
        onPartial = { partialTranscript.value = it },
        onSpeechStart = { controller.onUserSpeechStarted() },
        onFinal = { text ->
            partialTranscript.value = ""
            transcript.value = transcript.value + ("Vous" to text)
            controller.onFinalUtterance(text)
        },
        onError = { lastError.value = it },
    )

    fun start(handsFree: Boolean) { controller.start(); input.start() }
    fun stop() { controller.emergencyStop(); input.stop() }
    /** Bouton « Interrompre » : équivalent de « attends ». */
    fun interrupt() = controller.onFinalUtterance("attends")
}

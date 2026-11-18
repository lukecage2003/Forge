package ai.forge.voice

/** États visuels de Forge (§12.1). */
enum class PresenceState(val label: String) {
    STANDBY("En veille"),
    LISTENING("Écoute"),
    UNDERSTANDING("Compréhension"),
    THINKING("Réflexion"),
    RESPONDING("Réponse"),
    EXECUTING("Exécution"),
    ERROR("Erreur"),
}

/** Événements qui font évoluer la présence visuelle. */
sealed interface PresenceSignal {
    data class Voice(val state: VoiceState) : PresenceSignal
    /** Transcription partielle reçue : Forge « comprend » ce qui est dit. */
    data object PartialTranscript : PresenceSignal
    data object PlanReady : PresenceSignal
    data object ActionStarted : PresenceSignal
    data object ActionFinished : PresenceSignal
    data class Failure(val message: String) : PresenceSignal
    data object ErrorAcknowledged : PresenceSignal
}

/**
 * Réducteur pur : (état, signal) → état. Une erreur reste affichée jusqu'à acquittement
 * ou nouvelle écoute, pour ne jamais être masquée par une animation.
 */
object PresenceReducer {
    fun reduce(current: PresenceState, signal: PresenceSignal): PresenceState = when (signal) {
        is PresenceSignal.Failure -> PresenceState.ERROR
        PresenceSignal.ErrorAcknowledged -> PresenceState.STANDBY
        PresenceSignal.ActionStarted -> PresenceState.EXECUTING
        PresenceSignal.ActionFinished -> if (current == PresenceState.EXECUTING) PresenceState.STANDBY else current
        PresenceSignal.PartialTranscript -> if (current == PresenceState.ERROR) current else PresenceState.UNDERSTANDING
        PresenceSignal.PlanReady -> if (current == PresenceState.ERROR) current else PresenceState.THINKING
        is PresenceSignal.Voice -> when (signal.state) {
            VoiceState.IDLE -> if (current == PresenceState.ERROR) current else PresenceState.STANDBY
            VoiceState.LISTENING -> PresenceState.LISTENING
            VoiceState.THINKING -> if (current == PresenceState.ERROR) current else PresenceState.THINKING
            VoiceState.SPEAKING -> PresenceState.RESPONDING
        }
    }

    /** Niveau audio normalisé 0..1 à partir du RMS en dB fourni par SpeechRecognizer (≈ −2 à 10 dB). */
    fun audioLevel(rmsDb: Float): Float = ((rmsDb + 2f) / 12f).coerceIn(0f, 1f)
}

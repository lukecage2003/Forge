package ai.forge.voice

import kotlin.test.*

class PresenceTest {
    @Test fun lifecycleAndStickyError() {
        var s = PresenceState.STANDBY
        s = PresenceReducer.reduce(s, PresenceSignal.Voice(VoiceState.LISTENING)); assertEquals(PresenceState.LISTENING, s)
        s = PresenceReducer.reduce(s, PresenceSignal.PartialTranscript); assertEquals(PresenceState.UNDERSTANDING, s)
        s = PresenceReducer.reduce(s, PresenceSignal.PlanReady); assertEquals(PresenceState.THINKING, s)
        s = PresenceReducer.reduce(s, PresenceSignal.Voice(VoiceState.SPEAKING)); assertEquals(PresenceState.RESPONDING, s)
        s = PresenceReducer.reduce(s, PresenceSignal.ActionStarted); assertEquals(PresenceState.EXECUTING, s)
        s = PresenceReducer.reduce(s, PresenceSignal.ActionFinished); assertEquals(PresenceState.STANDBY, s)
        s = PresenceReducer.reduce(s, PresenceSignal.Failure("réseau")); assertEquals(PresenceState.ERROR, s)
        s = PresenceReducer.reduce(s, PresenceSignal.Voice(VoiceState.THINKING)); assertEquals(PresenceState.ERROR, s)
        s = PresenceReducer.reduce(s, PresenceSignal.Voice(VoiceState.LISTENING)); assertEquals(PresenceState.LISTENING, s)
        assertEquals(0f, PresenceReducer.audioLevel(-10f)); assertEquals(1f, PresenceReducer.audioLevel(20f))
    }
}

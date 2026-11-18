package ai.forge.voice

import ai.forge.voice.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class VoiceTest {
    @Test fun endpointerWaitsLongerAfterHesitation() {
        val e = Endpointer()
        assertFalse(e.isEndOfTurn("Forge, tu sais, je repensais au film et euh", 2_000))
        assertFalse(e.isEndOfTurn("Je pensais que", 2_000))
        assertFalse(e.isEndOfTurn("Je repensais au film Superman", 900))   // petite pause : on attend
        assertTrue(e.isEndOfTurn("Tu en penses quoi ?", 900))
        assertTrue(e.isEndOfTurn("Je repensais au film Superman de Christopher Reeve", 1_400))
    }

    @Test fun bargeInClassification() {
        val c = BargeInClassifier()
        assertEquals(BargeIn.StopAndListen, c.classify("Attends, laisse-moi terminer"))
        assertEquals(BargeIn.StopAndListen, c.classify("Stop."))
        val topic = c.classify("En fait, changeons de sujet, parle-moi de Batman")
        assertIs<BargeIn.ChangeTopic>(topic)
        assertEquals("parle-moi de batman", topic.remainder)
        assertEquals(BargeIn.Backchannel, c.classify("hmm"))
        assertEquals(BargeIn.EndSession, c.classify("Merci Forge c'est tout"))
        assertIs<BargeIn.NewUtterance>(c.classify("Et Lex Luthor dans tout ça ?"))
    }

    @Test fun chunkerEmitsSentencesAndStripsMarkdown() {
        val ch = SpeechChunker(minChars = 10)
        val out = mutableListOf<String>()
        "Le film de **1978** a posé les bases du genre. Voir https://exemple.org pour plus. Fin".split(" ")
            .forEach { out += ch.push("$it ") }
        ch.flush()?.let { out += it }
        assertEquals("Le film de 1978 a posé les bases du genre.", out[0])
        assertTrue(out[1].contains("le lien"))
        assertEquals("Fin", out.last())
    }

    @Test fun chunkerDoesNotSplitAfterAbbreviation() {
        val ch = SpeechChunker(minChars = 5)
        val segs = ch.push("Voir la p. 12 de l'édition originale. Ensuite ") 
        assertEquals(listOf("Voir la p. 12 de l'édition originale."), segs)
    }

    private class FakeOutput : SpeechOutput {
        val spoken = mutableListOf<String>()
        var stops = 0
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun speak(segment: String) { spoken += segment; gate?.await() }
        override fun stopNow() { stops++ }
    }

    @Test fun interruptionCancelsSpeechAndKeepsPartial() = runTest {
        val out = FakeOutput().apply { gate = CompletableDeferred() }
        var partial: String? = null
        val source = ResponseSource { flow {
            emit("Le Superman de Christopher Reeve reste une référence absolue du genre. ")
            delay(10)
            emit("Sa double identité était jouée avec une finesse remarquable. ")
        } }
        val ctl = VoiceSessionController(this, out, source, onInterruptedPartial = { partial = it })
        ctl.start()
        ctl.onFinalUtterance("Forge, tu sais, je repensais au film Superman de Christopher Reeve")
        advanceUntilIdle()
        assertEquals(VoiceState.SPEAKING, ctl.state.value)
        ctl.onUserSpeechStarted()
        ctl.onFinalUtterance("Attends, laisse-moi terminer")
        advanceUntilIdle()
        assertEquals(VoiceState.LISTENING, ctl.state.value)
        assertTrue(out.stops >= 1)
        assertEquals(1, out.spoken.size)
        assertNotNull(partial)
    }

    @Test fun backchannelDoesNotInterrupt() = runTest {
        val out = FakeOutput()
        val ctl = VoiceSessionController(this, out, { flow { emit("Bonne remarque à approfondir. ") } })
        ctl.start()
        ctl.onFinalUtterance("Parle-moi de Superman")
        ctl.onFinalUtterance("hmm")
        advanceUntilIdle()
        assertEquals(1, out.spoken.size)
        assertEquals(VoiceState.LISTENING, ctl.state.value)
    }

    @Test fun spokenTextIsCleanedOfSymbolsAndTags() {
        assertEquals("Le costume absorbe 40 pour cent des chocs, voir la page.", SpeechChunker.clean("**Le costume** absorbe 40% des chocs [B1] 🔥 → voir la page."))
        assertEquals("Avantage T'Challa.", SpeechChunker.clean("Avantage T'Challa ✅."))
    }

    private class PipelinedOutput : SpeechOutput {
        val queued = mutableListOf<String>(); var idleAwaited = 0
        override suspend fun speak(segment: String) { queued += segment }
        override suspend fun awaitIdle() { idleAwaited++ }
        override fun stopNow() {}
    }

    @Test fun sessionWaitsForRealEndOfSpeechBeforeListening() = runTest {
        val out = PipelinedOutput()
        val ctl = VoiceSessionController(this, out, { flow { emit("Première phrase assez longue pour être dite seule. "); emit("Deuxième phrase, elle aussi complète et naturelle. ") } })
        ctl.start(); ctl.onFinalUtterance("Parle-moi de Thor")
        advanceUntilIdle()
        assertEquals(1, out.idleAwaited)
        assertEquals(VoiceState.LISTENING, ctl.state.value)
        assertTrue(out.queued.isNotEmpty())
    }

    @Test fun continuationAfterPauseIsMergedNotReplacing() = runTest {
        val asked = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        val source = ResponseSource { u -> asked += u; flow { gate.await(); emit("Réponse complète et assez longue pour être dite. ") } }
        val ctl = VoiceSessionController(this, FakeOutput(), source, now = { 1_000 })
        ctl.start()
        ctl.onFinalUtterance("Je voudrais un script sur Black Panther")
        advanceUntilIdle()
        ctl.onFinalUtterance("et sur son costume en vibranium")      // suite après une pause
        gate.complete(Unit); advanceUntilIdle()
        assertEquals("Je voudrais un script sur Black Panther et sur son costume en vibranium", asked.last())
    }

    @Test fun newQuestionAfterForgeSpokeIsNotMerged() = runTest {
        val asked = mutableListOf<String>()
        val ctl = VoiceSessionController(this, FakeOutput(), { u -> asked += u; flow { emit("Une réponse complète et suffisamment longue pour être dite. ") } }, now = { 1_000 })
        ctl.start(); ctl.onFinalUtterance("Parle-moi de Thor"); advanceUntilIdle()
        ctl.onFinalUtterance("Et de Loki ?"); advanceUntilIdle()
        assertEquals("Et de Loki ?", asked.last())
    }
}

class FirstWordsTest {
    @Test fun firstSentenceIsSpokenEarlyThenNormalChunks() {
        val ch = SpeechChunker()
        assertEquals(listOf("Bonne idée."), ch.push("Bonne idée. Le"))
        assertEquals(emptyList(), ch.push(" reste est plus long."))       // ensuite, segments plus longs
        val c2 = SpeechChunker()
        assertEquals(1, c2.push("Le vibranium vient d'une météorite tombée au Wakanda, et ").size)   // coupe à la virgule
    }
}

class SpeechSegmenterTest {
    private fun tone(ms: Int, amp: Float) = FloatArray(16 * ms) { i -> (amp * kotlin.math.sin(i * 0.3)).toFloat() }
    private fun noise(ms: Int) = FloatArray(16 * ms) { i -> (0.002 * kotlin.math.sin(i * 1.7)).toFloat() }

    @Test fun segmentsSpeechBetweenSilences() {
        val s = SpeechSegmenter()
        val ev = mutableListOf<SpeechSegmenter.Event>()
        repeat(50) { ev += s.push(noise(20)) }                 // 1 s de bruit de fond
        repeat(100) { ev += s.push(tone(20, 0.3f)) }           // 2 s de parole
        repeat(80) { ev += s.push(noise(20)) }                 // 1,6 s de silence
        assertEquals(1, ev.count { it is SpeechSegmenter.Event.SpeechStart })
        assertTrue(ev.any { it is SpeechSegmenter.Event.Interim })
        val end = ev.filterIsInstance<SpeechSegmenter.Event.End>().single()
        assertTrue(end.audio.size in 16 * 2_000..16 * 3_600, "${end.audio.size}")   // parole + pré-roulement + silence final
    }

    @Test fun waitsLongerAfterAnUnfinishedSentence() {
        val s = SpeechSegmenter()
        repeat(25) { s.push(noise(20)) }
        repeat(50) { s.push(tone(20, 0.3f)) }
        s.lastInterim = "je voudrais savoir si et"              // phrase inachevée
        val ev = (0 until 90).flatMap { s.push(noise(20)) }    // 1,8 s de silence : pas encore fini
        assertTrue(ev.none { it is SpeechSegmenter.Event.End })
        s.lastInterim = "Quel est le pouvoir de Tornade ?"
        assertTrue((0 until 45).flatMap { s.push(noise(20)) }.any { it is SpeechSegmenter.Event.End })
    }

    @Test fun ignoresSteadyBackgroundNoise() {
        val s = SpeechSegmenter()
        val ev = (0 until 300).flatMap { s.push(tone(20, 0.004f)) }
        assertTrue(ev.isEmpty())
    }
}

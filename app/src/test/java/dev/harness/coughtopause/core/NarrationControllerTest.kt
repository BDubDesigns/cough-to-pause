package dev.harness.coughtopause.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * State-machine tests for the behaviour the experiment must prove:
 * interrupted line identity, resume-from-top-of-line, repeated cycles, and event ordering.
 */
class NarrationControllerTest {

    private var clock = 1_000L
    private val script = listOf(
        ScriptLine("L01", 0, "first line"),
        ScriptLine("L02", 1, "second line"),
        ScriptLine("L03", 2, "third line"),
    )

    private fun controller() = NarrationController(script, "test-script") { clock }

    private fun List<NarrationAction>.speaks() = filterIsInstance<NarrationAction.SpeakLine>()

    private fun List<NarrationAction>.stopped() = any { it is NarrationAction.StopSpeech }

    @Test
    fun startSpeaksFirstLineAndClearsCheckpoint() {
        val c = controller()
        val actions = c.start(CommandSource.UI)
        val speaks = actions.speaks()
        assertEquals(1, speaks.size)
        assertEquals(0, speaks.first().lineIndex)
        assertEquals("L01", speaks.first().lineId)
        assertTrue(actions.stopped())
        assertEquals(PlaybackStatus.PLAYING, c.snapshot().status)
        assertNull(c.snapshot().checkpoint)
    }

    @Test
    fun coughPausesAndSavesTheInterruptedLine() {
        val c = controller()
        c.start()
        clock += 500
        val actions = c.onCough(0.71f, "HYBRID")
        assertTrue("speech must stop on detection", actions.stopped())
        val snapshot = c.snapshot()
        assertEquals(PlaybackStatus.PAUSED, snapshot.status)
        val checkpoint = snapshot.checkpoint!!
        assertEquals("L01", checkpoint.lineId)
        assertEquals(0, checkpoint.lineIndex)
        assertEquals(PauseReason.COUGH, checkpoint.reason)
        assertEquals(1, snapshot.counters.coughPauses)
    }

    @Test
    fun resumeRestartsTheInterruptedLineAndNeverReplaysCompletedLines() {
        val c = controller()
        c.start()
        // Finish line 0 and line 1, so the third line is speaking when the cough happens.
        c.onLineCompleted(0, c.snapshot().generation)
        c.onLineCompleted(1, c.snapshot().generation)
        assertEquals("L03", c.snapshot().currentLineId)

        c.onCough(0.8f, "DSP")
        assertEquals("L03", c.snapshot().checkpoint?.lineId)

        val resumed = c.resume(CommandSource.VOICE, "play")
        val speaks = resumed.speaks()
        assertEquals(1, speaks.size)
        assertEquals("L03", speaks.first().lineId)
        assertTrue("the interrupted line restarts from its beginning", speaks.first().restart)
        assertEquals(2, speaks.first().lineIndex)
        assertEquals(PlaybackStatus.PLAYING, c.snapshot().status)
        assertNull(c.snapshot().checkpoint)
        assertEquals(1, c.snapshot().counters.resumes)
    }

    @Test
    fun repeatedPauseResumeCyclesStayStable() {
        val c = controller()
        c.start()
        repeat(20) { cycle ->
            clock += 100
            c.onCough(0.9f, "DSP")
            assertEquals("cycle $cycle", PlaybackStatus.PAUSED, c.snapshot().status)
            assertEquals("L01", c.snapshot().checkpoint?.lineId)
            clock += 100
            val actions = c.resume(CommandSource.VOICE, "resume")
            assertEquals("cycle $cycle", 1, actions.speaks().size)
            assertEquals("L01", actions.speaks().first().lineId)
            assertEquals("cycle $cycle", PlaybackStatus.PLAYING, c.snapshot().status)
        }
        assertEquals(20, c.snapshot().counters.coughPauses)
        assertEquals(20, c.snapshot().counters.resumes)
    }

    @Test
    fun coughWhilePausedKeepsTheCheckpointAndEmitsNoStop() {
        val c = controller()
        c.start()
        c.onCough(0.7f, "DSP")
        val checkpointAfterFirst = c.snapshot().checkpoint
        clock += 250
        val actions = c.onCough(0.95f, "DSP")
        assertFalse("already stopped: no second stop action", actions.stopped())
        assertEquals(checkpointAfterFirst, c.snapshot().checkpoint)
        assertEquals(1, c.snapshot().counters.coughPauses)
        assertEquals(1, c.snapshot().counters.ignoredCoughs)
        assertEquals(PlaybackStatus.PAUSED, c.snapshot().status)
    }

    @Test
    fun coughWhileIdleIsIgnored() {
        val c = controller()
        val actions = c.onCough(0.8f, "DSP")
        assertTrue(actions.speaks().isEmpty())
        assertEquals(PlaybackStatus.IDLE, c.snapshot().status)
        assertEquals(1, c.snapshot().counters.ignoredCoughs)
    }

    @Test
    fun lineCompletionAdvancesTheScriptWithoutCheckpoint() {
        val c = controller()
        c.start()
        val gen = c.snapshot().generation
        val actions = c.onLineCompleted(0, gen)
        assertEquals(1, actions.speaks().size)
        assertEquals("L02", actions.speaks().first().lineId)
        assertNull(c.snapshot().checkpoint)
        assertEquals(1, c.snapshot().counters.completedLines)
    }

    @Test
    fun staleCallbacksFromAnAbortedUtteranceAreIgnored() {
        val c = controller()
        c.start()
        val staleGeneration = c.snapshot().generation
        c.onCough(0.9f, "DSP") // bumps the generation, aborts the utterance
        val actions = c.onLineCompleted(0, staleGeneration)
        assertTrue("stale completion must not advance the script", actions.speaks().isEmpty())
        assertEquals(PlaybackStatus.PAUSED, c.snapshot().status)
        assertEquals("L01", c.snapshot().checkpoint?.lineId)
        assertEquals(1, c.snapshot().counters.staleCallbacks)

        // A stale start callback must not move the current line either.
        c.onLineStarted(2, staleGeneration)
        assertEquals("L01", c.snapshot().currentLineId)
    }

    @Test
    fun racingCoughAndCompletionKeepsOneConsistentOutcome() {
        // Cough arrives first, then the TTS completion callback for the aborted utterance.
        val c = controller()
        c.start()
        val generationAtSpeak = c.snapshot().generation
        c.onCough(0.66f, "HYBRID")
        val afterRace = c.onLineCompleted(0, generationAtSpeak)
        assertTrue(afterRace.speaks().isEmpty())
        assertEquals(PlaybackStatus.PAUSED, c.snapshot().status)
        assertEquals("L01", c.snapshot().checkpoint?.lineId)
    }

    @Test
    fun scriptCompletionStopsAndResumeIsANoop() {
        val c = controller()
        c.start()
        c.onLineCompleted(0, c.snapshot().generation)
        c.onLineCompleted(1, c.snapshot().generation)
        c.onLineCompleted(2, c.snapshot().generation)
        assertEquals(PlaybackStatus.COMPLETED, c.snapshot().status)
        assertEquals(3, c.snapshot().counters.completedLines)
        val resumed = c.resume(CommandSource.VOICE, "play")
        assertTrue(resumed.speaks().isEmpty())
    }

    @Test
    fun pauseCommandWhileIdleDoesNothing() {
        val c = controller()
        val actions = c.pause(CommandSource.MEDIA_BUTTON)
        assertFalse(actions.stopped())
        assertEquals(PlaybackStatus.IDLE, c.snapshot().status)
        assertEquals(0, c.snapshot().counters.manualPauses)
    }

    @Test
    fun voicePauseIsCountedSeparatelyFromCoughPause() {
        val c = controller()
        c.start()
        c.pause(CommandSource.VOICE)
        assertEquals(1, c.snapshot().counters.voicePauses)
        assertEquals(0, c.snapshot().counters.coughPauses)
        assertEquals(PauseReason.VOICE, c.snapshot().checkpoint?.reason)
    }

    @Test
    fun mediaButtonPauseMarksTheCheckpointReason() {
        val c = controller()
        c.start()
        c.onLineCompleted(0, c.snapshot().generation)
        c.pause(CommandSource.MEDIA_BUTTON)
        assertEquals("L02", c.snapshot().checkpoint?.lineId)
        assertEquals(PauseReason.MEDIA_BUTTON, c.snapshot().checkpoint?.reason)
        val actions = c.resume(CommandSource.MEDIA_BUTTON)
        assertEquals("L02", actions.speaks().first().lineId)
    }

    @Test
    fun restorePausedSessionResumesTheSameLine() {
        val c = controller()
        c.start()
        c.onLineCompleted(0, c.snapshot().generation)
        c.onCough(0.8f, "DSP")
        val session = c.exportSession(epochNow = 42L)

        val restored = controller()
        restored.restore(session)
        assertEquals(PlaybackStatus.PAUSED, restored.snapshot().status)
        assertEquals("L02", restored.snapshot().checkpoint?.lineId)
        assertEquals(1, restored.snapshot().counters.coughPauses)

        val actions = restored.resume(CommandSource.VOICE, "play")
        assertEquals("L02", actions.speaks().first().lineId)
    }

    @Test
    fun restoringASessionThatWasPlayingComesBackPausedNotSpeaking() {
        val c = controller()
        c.start()
        c.onLineCompleted(0, c.snapshot().generation)
        val session = c.exportSession(epochNow = 1L)
        assertEquals(PlaybackStatus.PLAYING, session.status)

        val restored = controller()
        val actions = restored.restore(session)
        assertTrue("restore must not start audio", actions.speaks().isEmpty())
        assertEquals(PlaybackStatus.PAUSED, restored.snapshot().status)
        assertEquals("L02", restored.snapshot().checkpoint?.lineId)
        assertEquals(PauseReason.RESTORED, restored.snapshot().checkpoint?.reason)
    }

    @Test
    fun restoreIgnoresSessionsFromAnotherScript() {
        val c = controller()
        val foreign = PersistedSession(
            scriptId = "other-script",
            status = PlaybackStatus.PAUSED,
            lineIndex = 1,
            lineId = "X",
            reason = PauseReason.COUGH,
            pausedAtMs = 1L,
            counters = Counters(),
            updatedAtEpochMs = 1L,
        )
        c.restore(foreign)
        assertEquals(PlaybackStatus.IDLE, c.snapshot().status)
    }

    @Test
    fun persistAndPublishActionsAccompanyEveryTransition() {
        val c = controller()
        val actions = c.start()
        assertTrue(actions.any { it is NarrationAction.Persist })
        assertTrue(actions.any { it is NarrationAction.Publish })
    }

    @Test
    fun loadingASceneScriptReplacesTheHarnessScriptAndKeepsThePauseContract() {
        val c = controller()
        c.start()
        c.onCough(0.8f, "DSP")

        val scene = listOf(
            ScriptLine("R01", 0, "Thornwick. A huddle of cottages."),
            ScriptLine("R02", 1, "Here: Mira, the innkeeper."),
        )
        val actions = c.loadScript("rpg-c-test-2", scene)
        assertTrue("loading a scene must stop current speech", actions.stopped())
        assertEquals(PlaybackStatus.IDLE, c.snapshot().status)
        assertNull("a new script starts without a checkpoint", c.snapshot().checkpoint)
        assertEquals("rpg-c-test-2", c.snapshot().scriptId)

        val started = c.start()
        assertEquals("R01", started.speaks().single().lineId)

        // The same cough → checkpoint → resume-lines contract applies to RPG narration.
        c.onCough(0.9f, "HYBRID")
        assertEquals("R01", c.snapshot().checkpoint?.lineId)
        val resumed = c.resume(CommandSource.VOICE, "play")
        assertEquals("R01", resumed.speaks().single().lineId)
        assertTrue(resumed.speaks().single().restart)
    }

    @Test
    fun generationIncreasesForEveryUtterance() {
        val c = controller()
        c.start()
        val first = c.snapshot().generation
        c.onLineCompleted(0, first)
        val second = c.snapshot().generation
        assertTrue(second > first)
        c.onCough(0.5f, "DSP")
        assertTrue(c.snapshot().generation > second)
        c.resume(CommandSource.UI)
        assertTrue(c.snapshot().generation > second + 1)
    }
}

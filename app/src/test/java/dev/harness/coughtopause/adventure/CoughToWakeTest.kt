package dev.harness.coughtopause.adventure

import dev.harness.coughtopause.core.CommandSource
import dev.harness.coughtopause.core.NarrationAction
import dev.harness.coughtopause.core.NarrationController
import dev.harness.coughtopause.core.PauseReason
import dev.harness.coughtopause.core.PlaybackStatus
import dev.harness.coughtopause.core.ScriptLine
import dev.harness.coughtopause.rpg.ActionId
import dev.harness.coughtopause.rpg.Backgrounds
import dev.harness.coughtopause.rpg.Campaign
import dev.harness.coughtopause.rpg.CampaignId
import dev.harness.coughtopause.rpg.CreateCampaignResult
import dev.harness.coughtopause.rpg.GameCommand
import dev.harness.coughtopause.rpg.Submission
import dev.harness.coughtopause.rpg.Validated
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Cough-to-wake: the whole hands-free recovery loop, as rules rather than as a phone session.
 *
 * Ten transitions, one test each, plus the paused-vs-completed distinction. Everything here is
 * deterministic — the microphone itself is measured on the device (docs/playtest.md) and never
 * claimed by these tests.
 */
class CoughToWakeTest {

    private var clock = 1_000L
    private val script = listOf(
        ScriptLine("L01", 0, "The road forks below the barrow."),
        ScriptLine("L02", 1, "A fire burns at the crossroads."),
    )

    private fun narration() = NarrationController(script, SCENE) { clock }

    private fun speaks(actions: List<NarrationAction>) =
        actions.filterIsInstance<NarrationAction.SpeakLine>()

    private fun engine(): AdventureEngine =
        AdventureEngine(File("build/tmp/cough-to-wake-${System.nanoTime()}"), clock = { 1_000L })

    private fun campaign(engine: AdventureEngine): Campaign {
        val rolled = engine.rollAttributes(9L)
        val assignment = engine.autoAssign(rolled, Backgrounds.CARAVAN_GUARD)
        val character = engine.createCharacter("Mira", rolled, assignment, Backgrounds.CARAVAN_GUARD)
        return (engine.createCampaign(
            CampaignId("c-wake"),
            9L,
            (character as Validated.Valid).value,
        ) as CreateCampaignResult.Created).campaign
    }

    // 1. Cough while narrating -> pause. It must never also open a listening window.
    @Test
    fun coughWhileNarratingPausesAndNeverOpensAWindow() {
        val policy = CoughWakePolicy()
        val c = narration()
        c.start()
        assertEquals(PlaybackStatus.PLAYING, c.snapshot().status)

        val wake = policy.onCough(
            detectionAtMs = clock,
            nowMs = clock,
            inAdventure = true,
            status = c.snapshot().status,
            scriptId = SCENE,
            windowOpen = false,
        )
        assertFalse("a cough during narration pauses; it does not open a window", wake)

        val actions = c.onCough(0.74f, "DSP")
        assertTrue("speech must stop on detection", actions.any { it is NarrationAction.StopSpeech })
        assertEquals(PlaybackStatus.PAUSED, c.snapshot().status)
        assertEquals("L01", c.snapshot().checkpoint?.lineId)
        assertEquals(PauseReason.COUGH, c.snapshot().checkpoint?.reason)
    }

    // 2. Play while paused -> the interrupted line restarts from its beginning.
    @Test
    fun playWhilePausedRestartsTheInterruptedLine() {
        val c = narration()
        c.start()
        c.onLineCompleted(0, c.snapshot().generation)
        assertEquals("L02", c.snapshot().currentLineId)

        c.onCough(0.8f, "DSP")
        assertEquals("L02", c.snapshot().checkpoint?.lineId)
        assertEquals(
            "while paused, \"Play\" resumes rather than replays",
            ReplayAction.RESUME,
            ReplayPolicy.decide(c.snapshot().status, hasLastScene = true),
        )

        val resumed = speaks(c.resume(CommandSource.VOICE, "play"))
        assertEquals(1, resumed.size)
        assertEquals("L02", resumed.first().lineId)
        assertTrue("the interrupted line restarts, not the completed ones", resumed.first().restart)
    }

    // 3. Narration completes -> the automatic response window opens, once per scene.
    @Test
    fun finishedSceneOpensTheResponseWindowExactlyOnce() {
        val policy = AutoListenPolicy()
        assertTrue(policy.shouldListen(true, PlaybackStatus.COMPLETED, SCENE, generation = 7L, alreadyListening = false))
        assertFalse(
            "the same scene must not open a second window",
            policy.shouldListen(true, PlaybackStatus.COMPLETED, SCENE, generation = 7L, alreadyListening = false),
        )
        assertFalse(
            "a window that is already open is never restarted",
            policy.shouldListen(true, PlaybackStatus.COMPLETED, "rpg-replay-1", generation = 8L, alreadyListening = true),
        )
        assertTrue(
            "a replayed prompt is a new generation, so it opens one more",
            policy.shouldListen(true, PlaybackStatus.COMPLETED, "rpg-replay-1", generation = 8L, alreadyListening = false),
        )
    }

    // 4. The window times out (or is answered) -> the detector gets the microphone back.
    @Test
    fun responseTimeoutRestoresTheCoughDetector() {
        val mic = MicOwnership()
        assertEquals(MicOwner.DETECTOR, mic.onPlaybackStatus(PlaybackStatus.COMPLETED))
        assertEquals(MicOwner.ADVENTURE_ASR, mic.openAdventureWindow())
        assertTrue(mic.isWindowOpen)

        assertEquals(
            "silence closes the window and returns the microphone to the detector",
            MicOwner.DETECTOR,
            mic.closeAdventureWindow(PlaybackStatus.COMPLETED),
        )
        assertFalse(mic.isWindowOpen)
    }

    // 5. Cough after the window expired -> a fresh window, with nothing replayed.
    @Test
    fun coughAfterTheWindowExpiresOpensAFreshWindow() {
        val policy = CoughWakePolicy()
        assertFalse(
            "a cough heard while the window is open changes nothing",
            policy.onCough(1_000L, 1_000L, true, PlaybackStatus.COMPLETED, SCENE, windowOpen = true),
        )
        assertFalse(
            "the same detection delivered twice is not a second cough",
            policy.onCough(1_000L, 2_000L, true, PlaybackStatus.COMPLETED, SCENE, windowOpen = false),
        )
        assertTrue(
            "a deliberate cough after the timeout opens a fresh window",
            policy.onCough(9_000L, 9_000L, true, PlaybackStatus.COMPLETED, SCENE, windowOpen = false),
        )
    }

    // 6. Repeated coughs -> only one window.
    @Test
    fun repeatedCoughsOpenOnlyOneWindow() {
        val policy = CoughWakePolicy(cooldownMs = 2_000L)
        assertTrue(policy.onCough(1_000L, 1_000L, true, PlaybackStatus.COMPLETED, SCENE, windowOpen = false))
        assertFalse(
            "a second cough while the window is open must not restart the recogniser",
            policy.onCough(1_500L, 1_500L, true, PlaybackStatus.COMPLETED, SCENE, windowOpen = true),
        )
        assertFalse(
            "a duplicate callback for the same event is ignored",
            policy.onCough(1_000L, 1_600L, true, PlaybackStatus.COMPLETED, SCENE, windowOpen = false),
        )
        assertFalse(
            "the detector cooldown still applies after the window closes",
            policy.onCough(2_000L, 2_000L, true, PlaybackStatus.COMPLETED, SCENE, windowOpen = false),
        )
        assertTrue(
            "a deliberate cough later does open one window",
            policy.onCough(4_500L, 4_500L, true, PlaybackStatus.COMPLETED, SCENE, windowOpen = false),
        )
    }

    // 7. Unrelated room speech -> ignored by an automatically opened window.
    @Test
    fun speechNotAddressedToAndyIsIgnored() {
        assertFalse(WakePhrase.acceptsAutoCapture("did you see the game last night"))
        assertFalse(WakePhrase.acceptsAutoCapture("candy shop opens at nine"))
        assertTrue("an addressed command still works", WakePhrase.acceptsAutoCapture("Andy, go to the inn"))

        val world = WorldView(locationId = "here", locations = emptyList(), npcs = emptyList())
        assertTrue(
            "unrelated speech is not turned into an action",
            IntentInterpreter().interpret("did you see the game last night", world) is AdventureIntent.Unknown,
        )
    }

    // 8. Replay -> the same prompt, and no second travel/check/XP/receipt.
    @Test
    fun replayNeverRepeatsCommittedGameState() {
        val engine = engine()
        val campaign = campaign(engine)
        val exit = campaign.world.locations[campaign.currentLocationId]!!.connections.first()
        assertTrue(
            "travel must commit for this test to mean anything",
            engine.submit(campaign.id, GameCommand.Travel(ActionId("a-travel"), exit)) is Submission.Committed,
        )

        val committed = engine.load(campaign.id)!!
        val actions = committed.appliedActions.size
        val events = committed.events.size
        val xp = committed.xpLedger
        val location = committed.currentLocationId

        // "Andy, repeat" parses as a replay request, never as a second command.
        val intent = IntentInterpreter().interpret("repeat", WorldView("here", emptyList(), emptyList()))
        assertEquals(AdventureIntent.Repeat, intent)
        assertEquals(
            "\"Play\" after a finished scene is the same replay request",
            AdventureIntent.Repeat,
            IntentInterpreter().interpret("play", WorldView("here", emptyList(), emptyList())),
        )
        assertEquals(
            "once narration has finished, repeat replays instead of resuming",
            ReplayAction.REPLAY,
            ReplayPolicy.decide(PlaybackStatus.COMPLETED, hasLastScene = true),
        )

        val after = engine.load(campaign.id)!!
        assertEquals("no extra committed action", actions, after.appliedActions.size)
        assertEquals("no extra event", events, after.events.size)
        assertEquals("no extra XP", xp, after.xpLedger)
        assertEquals("no second move", location, after.currentLocationId)
    }

    // 9. Leaving the adventure or quitting -> cough-to-wake is off; M0 scripts never wake.
    @Test
    fun leavingTheAdventureOrQuittingDisablesCoughToWake() {
        val policy = CoughWakePolicy()
        assertFalse(
            "character creation / M0 harness mode never wakes",
            policy.onCough(1_000L, 1_000L, inAdventure = false, PlaybackStatus.COMPLETED, SCENE, windowOpen = false),
        )
        assertTrue(policy.onCough(2_000L, 2_000L, true, PlaybackStatus.COMPLETED, SCENE, windowOpen = false))

        policy.reset() // leaving the Adventure screen resets the gesture
        assertFalse(
            "after leaving, a cough does nothing",
            policy.onCough(3_000L, 3_000L, inAdventure = false, PlaybackStatus.COMPLETED, SCENE, windowOpen = false),
        )
        assertFalse(
            "an M0 harness script id is never a wake source",
            policy.onCough(40_000L, 40_000L, true, PlaybackStatus.COMPLETED, "harness-script", windowOpen = false),
        )
        assertFalse(
            "a replayed game scene still has to be in the adventure",
            policy.onCough(80_000L, 80_000L, inAdventure = false, PlaybackStatus.COMPLETED, "rpg-replay-1", windowOpen = false),
        )
    }

    // 10. Microphone handoff: wait for the detector to let go; a timeout still recovers the mic.
    @Test
    fun microphoneHandoffTimeoutRecoversSafely() {
        assertEquals(
            "the detector still holds it: keep waiting",
            MicHandoff.WAITING,
            MicHandoff.evaluate(MicOwner.DETECTOR, serialServed = true, elapsedMs = 200L),
        )
        assertEquals(
            "the detector released it: the recogniser may start",
            MicHandoff.RELEASED,
            MicHandoff.evaluate(MicOwner.ADVENTURE_ASR, serialServed = true, elapsedMs = 240L),
        )
        assertEquals(
            "a leftover ADVENTURE_ASR from the previous window is not a release for this request",
            MicHandoff.WAITING,
            MicHandoff.evaluate(MicOwner.ADVENTURE_ASR, serialServed = false, elapsedMs = 40L),
        )
        assertEquals(
            "no release in time: start anyway and log it",
            MicHandoff.TIMED_OUT,
            MicHandoff.evaluate(MicOwner.ADVENTURE_ASR, serialServed = false, elapsedMs = MicHandoff.DEFAULT_TIMEOUT_MS),
        )

        // However the window ended — result, error or timeout — the detector returns.
        val mic = MicOwnership()
        mic.onPlaybackStatus(PlaybackStatus.COMPLETED)
        mic.openAdventureWindow()
        assertEquals(MicOwner.DETECTOR, mic.closeAdventureWindow(PlaybackStatus.COMPLETED))
        assertFalse(mic.isWindowOpen)
    }

    // 11. Paused and completed are different states: the same cough means different things.
    @Test
    fun pausedAndCompletedAreDistinctStates() {
        val policy = CoughWakePolicy()

        assertFalse(
            "a cough while paused only pauses; it must not open a gameplay window",
            policy.onCough(1_000L, 1_000L, true, PlaybackStatus.PAUSED, SCENE, windowOpen = false),
        )
        assertFalse(
            "and it still does nothing later in the pause, not just inside the cooldown",
            policy.onCough(50_000L, 50_000L, true, PlaybackStatus.PAUSED, SCENE, windowOpen = false),
        )
        assertTrue(
            "the identical cough once narration has completed is the wake gesture",
            policy.onCough(60_000L, 60_000L, true, PlaybackStatus.COMPLETED, SCENE, windowOpen = false),
        )

        // The button follows the same distinction: resume a line, or repeat a finished prompt.
        assertEquals(ReplayAction.RESUME, ReplayPolicy.decide(PlaybackStatus.PAUSED, hasLastScene = true))
        assertEquals(ReplayAction.REPLAY, ReplayPolicy.decide(PlaybackStatus.COMPLETED, hasLastScene = true))

        // ...and so does what the player sees, so resume is never hidden behind a "Repeat" label.
        assertEquals("Resume", ReplayPolicy.buttonLabel(PlaybackStatus.PAUSED))
        assertEquals("Repeat", ReplayPolicy.buttonLabel(PlaybackStatus.COMPLETED))
        assertTrue(
            "the paused state explains how to get back",
            ReplayPolicy.pausedHint(PlaybackStatus.PAUSED)?.contains("say \"Play\"") == true,
        )
        assertFalse(
            "and no paused explanation is shown once Andy has finished",
            ReplayPolicy.pausedHint(PlaybackStatus.COMPLETED) != null,
        )
    }

    private companion object {
        const val SCENE = "rpg-c-wake-1"
    }
}

package dev.harness.coughtopause.adventure

import dev.harness.coughtopause.core.PlaybackStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hands-free loop must not depend on the Activity being alive.
 *
 * Everything here drives [HandsFreeCoordinator] with ordinary values — no Context, no Compose, no
 * lifecycle — which is exactly how it runs inside the app-scoped controller. The microphone itself
 * is only exercised through [MicOwnership], which is the same authority the service uses.
 */
class HandsFreeCoordinatorTest {

    private val scene = "rpg-c-wake-1"

    /** A completed RPG scene with nothing else going on. */
    private fun completed(
        coordinator: HandsFreeCoordinator,
        detectionAtMs: Long? = null,
        generation: Long = 7L,
        windowOpen: Boolean = false,
        inAdventure: Boolean = true,
        status: PlaybackStatus = PlaybackStatus.COMPLETED,
        scriptId: String = scene,
        nowMs: Long = 10_000L,
        autoListen: Boolean = true,
    ) = coordinator.onState(
        status = status,
        scriptId = scriptId,
        generation = generation,
        detectionAtMs = detectionAtMs,
        inAdventure = inAdventure,
        autoListenEnabled = autoListen,
        windowOpen = windowOpen,
        nowMs = nowMs,
    )

    // 1. The loop runs on shared state alone; no UI callback is needed for it to work.
    @Test
    fun coordinationNeedsNoActivityAndNoUiCallbacks() {
        val coordinator = HandsFreeCoordinator()
        val request = completed(coordinator)
        assertEquals(HandsFreeCoordinator.Reason.NARRATION_COMPLETED, request?.reason)

        // Nothing else is called: no Activity, no Compose effect, no lifecycle event. The loop
        // still reacts to the next thing the service publishes.
        val wake = completed(coordinator, detectionAtMs = 30_000L, generation = 7L, nowMs = 30_000L)
        assertEquals(HandsFreeCoordinator.Reason.COUGH, wake?.reason)
    }

    // 2. Exactly one window per completed narration, however chatty the state source is.
    @Test
    fun oneWindowPerCompletedNarration() {
        val coordinator = HandsFreeCoordinator()
        val requests = (1..200).map { completed(coordinator) }.filterNotNull()
        assertEquals("a flood of identical states must not open more than one window", 1, requests.size)
    }

    // 3. A deliberate cough after the window expired wakes again, in COMPLETED.
    @Test
    fun coughWakesOnceTheWindowHasExpired() {
        val coordinator = HandsFreeCoordinator()
        assertNotNullRequest(completed(coordinator))
        // Window open: nothing.
        assertNull(completed(coordinator, detectionAtMs = 11_000L, windowOpen = true, nowMs = 11_000L))
        // Window closed, past the cooldown: the cough is the gesture.
        assertEquals(
            HandsFreeCoordinator.Reason.COUGH,
            completed(coordinator, detectionAtMs = 20_000L, nowMs = 20_000L)?.reason,
        )
        // A duplicate delivery of the same detection is not a second window.
        assertNull(completed(coordinator, detectionAtMs = 20_000L, nowMs = 20_100L))
    }

    // 4. No wake while paused: the pause contract is untouched.
    @Test
    fun noWakeWhilePaused() {
        val coordinator = HandsFreeCoordinator()
        assertNull(completed(coordinator, detectionAtMs = 5_000L, status = PlaybackStatus.PAUSED, nowMs = 5_000L))
        assertNull(completed(coordinator, detectionAtMs = 90_000L, status = PlaybackStatus.PAUSED, nowMs = 90_000L))
    }

    // 5. No wake in M0 harness mode, or outside the adventure screen.
    @Test
    fun noWakeInHarnessMode() {
        val coordinator = HandsFreeCoordinator()
        assertNull(
            completed(
                coordinator,
                detectionAtMs = 5_000L,
                inAdventure = false,
                scriptId = "harness-script",
                status = PlaybackStatus.COMPLETED,
            ),
        )
        assertNull(completed(coordinator, detectionAtMs = 6_000L, inAdventure = false))
        // Entering the adventure with that same finished scene is a new key: one window on arrival,
        // and exactly one.
        assertEquals(
            HandsFreeCoordinator.Reason.NARRATION_COMPLETED,
            completed(coordinator, detectionAtMs = 6_000L, inAdventure = true)?.reason,
        )
        assertNull(completed(coordinator, detectionAtMs = 6_000L, inAdventure = true))
    }

    // 6. During the handoff the window counts as open, so nothing asks for a second recogniser.
    @Test
    fun noDuplicatedRequestsWhileTheWindowIsPending() {
        val coordinator = HandsFreeCoordinator()
        assertNull("a scene completing while a window is pending stays silent", completed(coordinator, windowOpen = true))
        assertNull(completed(coordinator, detectionAtMs = 1_000L, windowOpen = true, nowMs = 1_000L))
        // Once it closes, the gesture works again.
        assertEquals(
            HandsFreeCoordinator.Reason.COUGH,
            completed(coordinator, detectionAtMs = 30_000L, nowMs = 30_000L)?.reason,
        )
    }

    // 7. Whichever way the window ends, the detector gets the microphone back.
    @Test
    fun detectorReturnsAfterTimeoutFailureAndCancellation() {
        for (end in listOf("timeout", "error", "cancel")) {
            val mic = MicOwnership()
            mic.onPlaybackStatus(PlaybackStatus.COMPLETED)
            assertEquals("window open for $end", MicOwner.ADVENTURE_ASR, mic.openAdventureWindow())
            assertEquals("window close for $end", MicOwner.DETECTOR, mic.closeAdventureWindow(PlaybackStatus.COMPLETED))
            assertFalse("no window left open after $end", mic.isWindowOpen)
        }
    }

    // 8. A stale capture callback never turns into an action.
    @Test
    fun staleCallbacksDoNotAct() {
        var now = 1_000L
        val capture = VoiceCapture(clock = { now }, timeoutMs = 5_000L)
        assertEquals(VoiceState.Listening, capture.start())
        now += 6_000L
        assertEquals(VoiceState.TimedOut, capture.tick())
        // The recogniser answers late: the window is already over.
        assertEquals(VoiceState.TimedOut, capture.onResult("Andy, go to the inn"))
        capture.reset()
        assertEquals(VoiceState.Idle, capture.state)
        assertEquals(VoiceState.Idle, capture.onResult("Andy, go to the inn"))
    }

    // 9. A replay is a new narration generation: one more window, still no second game action.
    @Test
    fun replayOpensOneMoreWindowAndRepeatsNothing() {
        val coordinator = HandsFreeCoordinator()
        assertNotNullRequest(completed(coordinator, generation = 7L))
        assertNull("same scene, same generation", completed(coordinator, generation = 7L))

        // Replaying loads a fresh rpg-replay-* script: new generation, one new window.
        assertEquals(
            HandsFreeCoordinator.Reason.NARRATION_COMPLETED,
            completed(coordinator, scriptId = "rpg-replay-42", generation = 8L)?.reason,
        )
        assertNull(completed(coordinator, scriptId = "rpg-replay-42", generation = 8L))
        assertEquals(
            "the replay decision never becomes a game command",
            ReplayAction.REPLAY,
            ReplayPolicy.decide(PlaybackStatus.COMPLETED, hasLastScene = true),
        )
        assertEquals(
            AdventureIntent.Repeat,
            IntentInterpreter().interpret("repeat", WorldView("here", emptyList(), emptyList())),
        )
    }

    // 10. Quit/leave resets the loop, so nothing can wake a stopped game.
    @Test
    fun quitResetsTheLoopAndReturnsTheMicrophone() {
        val coordinator = HandsFreeCoordinator()
        assertNotNullRequest(completed(coordinator))
        coordinator.reset()

        // Quit stops narration, so the next published state is IDLE — and a cough then is inert.
        assertNull(completed(coordinator, status = PlaybackStatus.IDLE))
        assertNull(
            "after quit a cough cannot wake a stopped game",
            completed(coordinator, detectionAtMs = 50_000L, status = PlaybackStatus.IDLE, nowMs = 50_000L),
        )

        val mic = MicOwnership()
        mic.onPlaybackStatus(PlaybackStatus.COMPLETED)
        mic.openAdventureWindow()
        assertEquals(MicOwner.DETECTOR, mic.closeAdventureWindow(PlaybackStatus.COMPLETED))
    }

    private fun assertNotNullRequest(request: HandsFreeCoordinator.Request?) {
        assertTrue("expected a listening-window request", request != null)
    }
}

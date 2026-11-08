package dev.harness.coughtopause.adventure

import dev.harness.coughtopause.core.PlaybackStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakePhraseTest {

    @Test
    fun stripsTheNameFromTypedAndSpokenCommands() {
        assertEquals("go to the inn", WakePhrase.strip("Andy, go to the inn"))
        assertEquals("look around", WakePhrase.strip("hey Andy look around"))
        assertEquals("search the room", WakePhrase.strip("ANDY: search the room"))
        assertEquals("persuade Mira", WakePhrase.strip("okay andy — persuade Mira"))
    }

    @Test
    fun leavesOrdinaryTextAlone() {
        assertEquals("go to the inn", WakePhrase.strip("go to the inn"))
        assertEquals("candy shop", WakePhrase.strip("candy shop"))
        assertEquals("", WakePhrase.strip("   "))
    }

    @Test
    fun addressingAndyAloneAsksForNarration() {
        assertEquals("look", WakePhrase.actionFrom("Andy?"))
        assertEquals("look", WakePhrase.actionFrom("hey andy"))
        assertEquals("go to the inn", WakePhrase.actionFrom("Andy, go to the inn"))
    }

    @Test
    fun detectsWhetherThePlayerAddressedAndy() {
        assertTrue(WakePhrase.isAddressed("Andy, look"))
        assertTrue(WakePhrase.isAddressed("hey andy"))
        assertFalse(WakePhrase.isAddressed("look around"))
        assertFalse(WakePhrase.isAddressed("candy shop"))
    }
}

/**
 * The microphone handoff the PR review asked for: one owner at a time, and an adventure window
 * that always wins while it is open.
 */
class MicOwnershipTest {

    @Test
    fun detectorOwnsTheMicWhileNarrating() {
        val mic = MicOwnership()
        assertEquals(MicOwner.DETECTOR, mic.onPlaybackStatus(PlaybackStatus.PLAYING))
        assertEquals(MicOwner.DETECTOR, mic.onPlaybackStatus(PlaybackStatus.IDLE))
        assertEquals(MicOwner.DETECTOR, mic.onPlaybackStatus(PlaybackStatus.COMPLETED))
    }

    @Test
    fun harnessRecogniserOwnsTheMicWhilePaused() {
        val mic = MicOwnership()
        assertEquals(MicOwner.HARNESS_ASR, mic.onPlaybackStatus(PlaybackStatus.PAUSED))
    }

    @Test
    fun adventureWindowTakesTheMicFromTheDetector() {
        val mic = MicOwnership()
        mic.onPlaybackStatus(PlaybackStatus.PLAYING)
        assertEquals(MicOwner.ADVENTURE_ASR, mic.openAdventureWindow())
        assertTrue(mic.isWindowOpen)
    }

    @Test
    fun playbackChangesDoNotStealTheMicBackDuringAWindow() {
        val mic = MicOwnership()
        mic.onPlaybackStatus(PlaybackStatus.PLAYING)
        mic.openAdventureWindow()
        // A cough pauses narration mid-window; the player is still speaking.
        assertEquals(MicOwner.ADVENTURE_ASR, mic.onPlaybackStatus(PlaybackStatus.PAUSED))
        assertEquals(MicOwner.ADVENTURE_ASR, mic.onPlaybackStatus(PlaybackStatus.PLAYING))
    }

    @Test
    fun closingTheWindowRestoresThePlaybackPolicy() {
        val mic = MicOwnership()
        mic.onPlaybackStatus(PlaybackStatus.PLAYING)
        mic.openAdventureWindow()
        assertEquals(MicOwner.DETECTOR, mic.closeAdventureWindow(PlaybackStatus.PLAYING))
        assertFalse(mic.isWindowOpen)

        mic.openAdventureWindow()
        assertEquals(MicOwner.HARNESS_ASR, mic.closeAdventureWindow(PlaybackStatus.PAUSED))
    }

    @Test
    fun closingAnUnopenedWindowIsHarmless() {
        val mic = MicOwnership()
        mic.onPlaybackStatus(PlaybackStatus.PLAYING)
        assertEquals(MicOwner.DETECTOR, mic.closeAdventureWindow(PlaybackStatus.PLAYING))
    }

    @Test
    fun onlySpeechAddressingAndyIsActedOnFromTheAutoWindow() {
        assertTrue(WakePhrase.acceptsAutoCapture("Andy, look around"))
        assertTrue(WakePhrase.acceptsAutoCapture("hey andy go to the inn"))
        assertFalse("room chatter must be ignored", WakePhrase.acceptsAutoCapture("did you see the music games"))
        assertFalse(WakePhrase.acceptsAutoCapture("look around"))
    }
}

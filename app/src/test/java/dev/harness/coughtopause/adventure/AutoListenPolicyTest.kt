package dev.harness.coughtopause.adventure

import dev.harness.coughtopause.core.PlaybackStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contextual response window: after Andy finishes an RPG scene the app may listen once.
 * These tests cover the rule, not the microphone — that is measured on the device.
 */
class AutoListenPolicyTest {

    private val scene = "rpg-c-123-4"

    @Test
    fun listensWhenASceneFinishes() {
        val policy = AutoListenPolicy()
        assertTrue(
            policy.shouldListen(
                enabled = true,
                status = PlaybackStatus.COMPLETED,
                scriptId = scene,
                generation = 5L,
                alreadyListening = false,
            ),
        )
    }

    @Test
    fun doesNothingWhileNarratingOrPaused() {
        val policy = AutoListenPolicy()
        assertFalse(
            policy.shouldListen(true, PlaybackStatus.PLAYING, scene, 5L, false),
        )
        assertFalse(
            policy.shouldListen(true, PlaybackStatus.PAUSED, scene, 5L, false),
        )
        assertFalse(
            policy.shouldListen(true, PlaybackStatus.IDLE, scene, 5L, false),
        )
    }

    @Test
    fun ignoresTheHarnessScript() {
        val policy = AutoListenPolicy()
        assertFalse(
            "the M0 harness must keep its own behaviour",
            policy.shouldListen(true, PlaybackStatus.COMPLETED, "harness-v1", 5L, false),
        )
    }

    @Test
    fun oneSceneOpensOneWindow() {
        val policy = AutoListenPolicy()
        assertTrue(policy.shouldListen(true, PlaybackStatus.COMPLETED, scene, 5L, false))
        assertFalse(
            "the same generation must not reopen a window",
            policy.shouldListen(true, PlaybackStatus.COMPLETED, scene, 5L, false),
        )
        assertTrue(
            "a later scene may open a new window",
            policy.shouldListen(true, PlaybackStatus.COMPLETED, scene, 6L, false),
        )
    }

    @Test
    fun disabledNeverListens() {
        val policy = AutoListenPolicy()
        assertFalse(policy.shouldListen(false, PlaybackStatus.COMPLETED, scene, 5L, false))
    }

    @Test
    fun anOpenWindowIsNotReopened() {
        val policy = AutoListenPolicy()
        assertFalse(policy.shouldListen(true, PlaybackStatus.COMPLETED, scene, 5L, alreadyListening = true))
    }

    @Test
    fun resetAllowsTheSameSceneAgainAfterAnExplicitTap() {
        val policy = AutoListenPolicy()
        assertTrue(policy.shouldListen(true, PlaybackStatus.COMPLETED, scene, 5L, false))
        policy.reset()
        assertTrue(policy.shouldListen(true, PlaybackStatus.COMPLETED, scene, 5L, false))
    }
}

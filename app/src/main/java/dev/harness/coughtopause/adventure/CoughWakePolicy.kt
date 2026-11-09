package dev.harness.coughtopause.adventure

import dev.harness.coughtopause.core.PlaybackStatus

// Cough as a discreet wake gesture: once Andy has finished speaking and no window is open, a
// cough opens the same bounded contextual window without replaying narration or touching state.
object CoughWakePolicy {
    const val SCENE_PREFIX = "rpg-"

    fun shouldWake(
        inAdventure: Boolean,
        status: PlaybackStatus,
        scriptId: String,
        windowOpen: Boolean,
        sinceLastWakeMs: Long,
        cooldownMs: Long = 2_000L,
    ): Boolean =
        inAdventure &&
            status == PlaybackStatus.COMPLETED &&
            scriptId.startsWith(SCENE_PREFIX) &&
            !windowOpen &&
            sinceLastWakeMs >= cooldownMs
}

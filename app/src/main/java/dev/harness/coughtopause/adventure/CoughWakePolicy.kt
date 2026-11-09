package dev.harness.coughtopause.adventure

import dev.harness.coughtopause.core.PlaybackStatus

/**
 * Cough as a discreet wake gesture: once Andy has finished speaking and no window is open, a cough
 * opens the same bounded contextual window a finished scene opens — without replaying narration
 * and without touching game state.
 *
 * Stateful on purpose. The two things that go wrong on a phone are a duplicate delivery of one
 * detection and a second cough arriving while a window is already open, so both are settled here
 * and unit tested rather than debugged on the device.
 */
class CoughWakePolicy(private val cooldownMs: Long = DEFAULT_COOLDOWN_MS) {

    private var lastDetectionAtMs: Long? = null
    private var lastWakeAtMs: Long? = null

    fun reset() {
        lastDetectionAtMs = null
        lastWakeAtMs = null
    }

    /**
     * One cough. Returns true for exactly one cough per window: the caller opens the window.
     *
     * @param detectionAtMs the detector's own timestamp. Used to drop a duplicate delivery of the
     *   same event — the UI observes detection state, and Compose can hand it over more than once.
     * @param nowMs a monotonic clock on the same base as [detectionAtMs] (elapsed realtime).
     */
    fun onCough(
        detectionAtMs: Long,
        nowMs: Long,
        inAdventure: Boolean,
        status: PlaybackStatus,
        scriptId: String,
        windowOpen: Boolean,
    ): Boolean {
        if (lastDetectionAtMs == detectionAtMs) return false
        lastDetectionAtMs = detectionAtMs

        // M0 harness mode, character creation, or after leaving the adventure: nothing to wake.
        if (!inAdventure) return false
        // While narrating the cough pauses; while paused Play/Resume owns the transition.
        if (status != PlaybackStatus.COMPLETED) return false
        // Only scenes this screen narrated, so an M0 script completion cannot open a game window.
        if (!scriptId.startsWith(SCENE_PREFIX)) return false
        // Already listening: never restart the recogniser on top of itself.
        if (windowOpen) return false

        val previousWake = lastWakeAtMs
        if (previousWake != null && nowMs - previousWake < cooldownMs) return false

        lastWakeAtMs = nowMs
        return true
    }

    companion object {
        const val SCENE_PREFIX = "rpg-"

        /** Short enough to feel instant, long enough to swallow a double detection. */
        const val DEFAULT_COOLDOWN_MS = 2_000L
    }
}

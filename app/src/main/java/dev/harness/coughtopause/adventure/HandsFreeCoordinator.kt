package dev.harness.coughtopause.adventure

import dev.harness.coughtopause.core.PlaybackStatus

/**
 * The hands-free loop's decision layer: shared state in, at most one window request out.
 *
 * Deliberately free of Android types and of any Activity reference. The loop has to keep running
 * while the Activity is stopped or the screen is off, so nothing here may depend on the UI
 * lifecycle — the caller feeds it state and owns the side effects.
 */
class HandsFreeCoordinator(
    private val autoListen: AutoListenPolicy = AutoListenPolicy(),
    private val coughWake: CoughWakePolicy = CoughWakePolicy(),
) {

    enum class Reason {
        /** Andy finished a scene: the automatic contextual window. */
        NARRATION_COMPLETED,

        /** A deliberate cough after the window had already expired. */
        COUGH,
    }

    data class Request(val reason: Reason)

    /** One window per narration generation, not per state emission. */
    private data class NarrationKey(
        val status: PlaybackStatus,
        val scriptId: String,
        val generation: Long,
        val inAdventure: Boolean,
    )

    private var lastNarration: NarrationKey? = null

    /** Leaving the adventure or quitting: forget the scene and the gesture. */
    fun reset() {
        lastNarration = null
        autoListen.reset()
        coughWake.reset()
    }

    /** Toggling auto-listen restarts the "one window per scene" bookkeeping. */
    fun resetAutoListen() {
        autoListen.reset()
    }

    /**
     * Feed one snapshot of shared state. Safe to call as often as state changes: identical
     * snapshots never produce a second request, so a chatty collector cannot open two windows.
     *
     * @param windowOpen a window is open *or* requested and waiting for the microphone.
     */
    fun onState(
        status: PlaybackStatus,
        scriptId: String,
        generation: Long,
        detectionAtMs: Long?,
        inAdventure: Boolean,
        autoListenEnabled: Boolean,
        windowOpen: Boolean,
        nowMs: Long,
    ): Request? {
        val key = NarrationKey(status, scriptId, generation, inAdventure)
        val narrationRequest = if (key != lastNarration) {
            lastNarration = key
            // The automatic window is a game affordance: it only exists on the Adventure screen.
            // Entering the adventure with a finished scene counts as a new key, so the player gets
            // one window on arrival — and none anywhere else, M0 harness included.
            if (inAdventure && autoListen.shouldListen(autoListenEnabled, status, scriptId, generation, windowOpen)) {
                Request(Reason.NARRATION_COMPLETED)
            } else {
                null
            }
        } else {
            null
        }
        // Andy finishing wins: both lead to one window, but the completion is the reason to log.
        if (narrationRequest != null) return narrationRequest

        if (detectionAtMs != null &&
            coughWake.onCough(detectionAtMs, nowMs, inAdventure, status, scriptId, windowOpen)
        ) {
            return Request(Reason.COUGH)
        }
        return null
    }
}

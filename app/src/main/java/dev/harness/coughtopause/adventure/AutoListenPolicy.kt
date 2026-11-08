package dev.harness.coughtopause.adventure

import dev.harness.coughtopause.core.PlaybackStatus

/**
 * Decides whether the app should open a listening window by itself after Andy finishes speaking.
 *
 * Pure on purpose: the "should we listen now" rule is the part that races with narration
 * callbacks and coughs, so it is unit tested rather than debugged on a phone.
 */
class AutoListenPolicy {

    /** Which narration generation the policy has already acted on, so one scene opens one window. */
    private var handledGeneration: Long = Long.MIN_VALUE

    fun reset() {
        handledGeneration = Long.MIN_VALUE
    }

    fun shouldListen(
        enabled: Boolean,
        status: PlaybackStatus,
        scriptId: String,
        generation: Long,
        alreadyListening: Boolean,
    ): Boolean {
        if (!enabled) return false
        if (alreadyListening) return false
        // Only RPG scenes: the M0 harness script must keep its own behaviour.
        if (!scriptId.startsWith(SCENE_PREFIX)) return false
        if (generation == handledGeneration) return false
        if (status != PlaybackStatus.COMPLETED) return false
        handledGeneration = generation
        return true
    }

    companion object {
        const val SCENE_PREFIX = "rpg-"
    }
}

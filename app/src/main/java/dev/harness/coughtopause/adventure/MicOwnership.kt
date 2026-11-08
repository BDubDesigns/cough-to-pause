package dev.harness.coughtopause.adventure

import dev.harness.coughtopause.core.PlaybackStatus

/** Who is allowed to hold the microphone right now. Android grants capture to one client at a time. */
enum class MicOwner {
    /** The cough detector's AudioRecord. Continuous while narrating. */
    DETECTOR,

    /** The harness command recogniser ("play", "resume", "pause"). */
    HARNESS_ASR,

    /** A one-shot recogniser opened by the adventure screen. */
    ADVENTURE_ASR,

    NONE,
}

/**
 * The single source of truth for microphone ownership.
 *
 * Rules, in priority order:
 *  1. An open adventure voice window always wins — the player explicitly asked to speak.
 *  2. While paused, the harness recogniser owns the mic (coughs are ignored while paused anyway).
 *  3. Otherwise the detector owns it, so cough detection is continuous during narration.
 *
 * Pure and unit tested: the service only translates the returned owner into start/stop calls.
 */
class MicOwnership {

    var owner: MicOwner = MicOwner.NONE
        private set

    var isWindowOpen: Boolean = false
        private set

    /** Recompute the owner for a playback state change. */
    fun onPlaybackStatus(status: PlaybackStatus): MicOwner {
        if (isWindowOpen) return owner
        owner = when (status) {
            PlaybackStatus.PAUSED -> MicOwner.HARNESS_ASR
            PlaybackStatus.PLAYING, PlaybackStatus.IDLE, PlaybackStatus.COMPLETED -> MicOwner.DETECTOR
        }
        return owner
    }

    /** The player asked to speak: hand the mic over regardless of playback state. */
    fun openAdventureWindow(): MicOwner {
        isWindowOpen = true
        owner = MicOwner.ADVENTURE_ASR
        return owner
    }

    /** The window closed (result, error or timeout): fall back to the playback policy. */
    fun closeAdventureWindow(status: PlaybackStatus): MicOwner {
        isWindowOpen = false
        owner = when (status) {
            PlaybackStatus.PAUSED -> MicOwner.HARNESS_ASR
            PlaybackStatus.PLAYING, PlaybackStatus.IDLE, PlaybackStatus.COMPLETED -> MicOwner.DETECTOR
        }
        return owner
    }
}

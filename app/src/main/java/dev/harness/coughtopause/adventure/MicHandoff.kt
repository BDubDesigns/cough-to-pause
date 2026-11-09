package dev.harness.coughtopause.adventure

/**
 * Wait state for handing the microphone from the cough detector to the adventure recogniser.
 *
 * Asking the service to open the window is asynchronous, so constructing a SpeechRecognizer
 * straight afterwards races the detector for the microphone and records silence. The screen polls
 * this instead of guessing a delay, and recovers either way.
 */
enum class MicHandoff {
    /** The service has not finished releasing the detector yet. Keep waiting. */
    WAITING,

    /** The detector let go: the recogniser may start. */
    RELEASED,

    /** Nothing released the microphone in time. Start anyway, but say so. */
    TIMED_OUT,
    ;

    companion object {
        const val DEFAULT_TIMEOUT_MS = 1_200L

        /** How long to wait between polls while a handoff is pending. */
        const val POLL_MS = 40L

        fun evaluate(owner: MicOwner, elapsedMs: Long, timeoutMs: Long = DEFAULT_TIMEOUT_MS): MicHandoff =
            when {
                owner == MicOwner.ADVENTURE_ASR -> RELEASED
                elapsedMs >= timeoutMs -> TIMED_OUT
                else -> WAITING
            }
    }
}

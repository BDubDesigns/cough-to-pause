package dev.harness.coughtopause.adventure

import dev.harness.coughtopause.core.PlaybackStatus

/** What an "Andy, play" / "Andy, repeat" utterance should do, given where narration stopped. */
enum class ReplayAction {
    /** Narration is paused: resume the interrupted line from its beginning. */
    RESUME,

    /** Narration finished: speak the saved prompt again. Never re-runs rules or effects. */
    REPLAY,

    /** Nothing to do; say so rather than inventing an action. */
    IGNORE,
}

/**
 * "Play" means two things depending on where narration stopped. Keeping the choice pure means the
 * difference between resuming and re-narrating is tested, not discovered on a phone.
 */
object ReplayPolicy {
    fun decide(status: PlaybackStatus, hasLastScene: Boolean): ReplayAction = when {
        status == PlaybackStatus.PAUSED -> ReplayAction.RESUME
        status == PlaybackStatus.COMPLETED && hasLastScene -> ReplayAction.REPLAY
        else -> ReplayAction.IGNORE
    }

    /** The button under the text field: it resumes while paused, repeats once Andy has finished. */
    fun buttonLabel(status: PlaybackStatus): String =
        if (status == PlaybackStatus.PAUSED) "Resume" else "Repeat"

    /** Shown while paused so the way back is visible, not just spoken. Null when not paused. */
    fun pausedHint(status: PlaybackStatus): String? = when {
        status == PlaybackStatus.PAUSED ->
            "Paused — say \"Play\" or tap Resume. Coughing again while paused does nothing; " +
                "a cough only opens a listening window once Andy has finished."
        else -> null
    }
}

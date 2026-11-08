package dev.harness.coughtopause.adventure

/**
 * One-shot voice capture state machine.
 *
 * Kept free of Android types so the timeout and late-callback behaviour can be unit tested: a
 * recogniser that never answers must not leave the UI stuck in "listening".
 */
sealed interface VoiceState {
    data object Idle : VoiceState
    data object Listening : VoiceState
    data class Captured(val text: String) : VoiceState
    data class Failed(val reason: String) : VoiceState
    data object TimedOut : VoiceState
}

class VoiceCapture(
    private val clock: () -> Long,
    private val timeoutMs: Long = 7_000,
) {
    var state: VoiceState = VoiceState.Idle
        private set

    private var startedAt = 0L

    fun start(): VoiceState {
        startedAt = clock()
        state = VoiceState.Listening
        return state
    }

    fun onResult(text: String?): VoiceState {
        if (state != VoiceState.Listening) return state
        val trimmed = text?.trim()
        state = if (trimmed.isNullOrEmpty()) {
            VoiceState.Failed("nothing recognised")
        } else {
            VoiceState.Captured(trimmed)
        }
        return state
    }

    fun onError(code: Int): VoiceState {
        if (state != VoiceState.Listening) return state
        state = VoiceState.Failed("recogniser error $code")
        return state
    }

    /** Call on a timer; returns TimedOut once the capture has overrun. */
    fun tick(): VoiceState {
        if (state == VoiceState.Listening && clock() - startedAt >= timeoutMs) {
            state = VoiceState.TimedOut
        }
        return state
    }

    fun reset() {
        state = VoiceState.Idle
        startedAt = 0L
    }

    val isListening: Boolean get() = state == VoiceState.Listening
}

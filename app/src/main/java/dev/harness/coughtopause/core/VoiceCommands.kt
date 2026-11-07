package dev.harness.coughtopause.core

/** The complete command vocabulary. Nothing else is acted on. */
enum class VoiceCommand {
    PLAY,
    RESUME,
    PAUSE,
    STOP,
}

/**
 * Restricted-vocabulary command parsing.
 *
 * The recogniser is free-form, so the harness constrains it afterwards: only these words have any
 * effect, and a phrase that contains several of them is resolved by the *last* one spoken, which
 * matches how a person corrects themselves ("pause — no, resume").
 */
object VoiceCommands {

    val vocabulary: List<String> = listOf("play", "resume", "pause", "stop", "continue")

    fun parse(raw: String?): VoiceCommand? {
        if (raw.isNullOrBlank()) return null
        val words = raw.lowercase()
            .replace(Regex("[^a-z ]"), " ")
            .split(' ')
            .filter { it.isNotBlank() }
        if (words.isEmpty()) return null
        var found: VoiceCommand? = null
        for (word in words) {
            when (word) {
                "play" -> found = VoiceCommand.PLAY
                "resume" -> found = VoiceCommand.RESUME
                "pause" -> found = VoiceCommand.PAUSE
                "stop" -> found = VoiceCommand.STOP
                "continue" -> found = VoiceCommand.RESUME
                else -> Unit
            }
        }
        return found
    }
}

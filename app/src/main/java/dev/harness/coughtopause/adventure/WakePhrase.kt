package dev.harness.coughtopause.adventure

/**
 * The dungeon master's name, and the optional spoken/typed prefix that addresses it.
 *
 * This is *not* a wake word: nothing listens for "Andy" in the background. It only strips a
 * leading address from text that was already captured (typed, or after the player tapped Speak),
 * so "Andy, go to the inn" and "go to the inn" mean the same thing.
 */
object WakePhrase {

    const val NAME = "Andy"

    private val PREFIX = Regex(
        "^\\s*(?:(?:hey|hi|ok|okay|oi)\\s+)?andy\\b[\\s,.:;!?\\-–—]*",
        RegexOption.IGNORE_CASE,
    )

    /** True when the text starts by addressing Andy. */
    fun isAddressed(text: String): Boolean = PREFIX.containsMatchIn(text)

    /** Removes a leading "Andy," / "Hey Andy" and trims. Returns the input unchanged otherwise. */
    fun strip(text: String): String = text.replaceFirst(PREFIX, "").trim()

    /**
     * The action to run: the stripped text, or "look" when the player only said the name
     * ("Andy?" is a request for the DM to speak, not an empty command).
     */
    fun actionFrom(text: String): String {
        val stripped = strip(text)
        return if (stripped.isEmpty() && isAddressed(text)) "look" else stripped
    }
}

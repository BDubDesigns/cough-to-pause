package dev.harness.coughtopause.core

/**
 * A narration line. The harness narrates a fixed script so that a checkpoint can be
 * expressed as (line id, position in sequence) instead of a byte offset in a TTS stream.
 */
data class ScriptLine(
    val id: String,
    val index: Int,
    val text: String,
)

/**
 * Fixed narration script. Twelve lines, each short enough that a deliberately faked cough
 * fits inside a single line, and long enough to measure detection-to-stop latency.
 */
object NarrationScript {

    const val SCRIPT_ID = "harness-v1"

    val LINES: List<ScriptLine> = listOf(
        "The lantern keeper had not spoken in eleven years, and the harbour had grown used to his silence.",
        "On the morning the fog lifted early, a small boat came in without a sail or a name painted on its side.",
        "Mira counted the oars: four. She counted the seats: four. She counted the crew: none.",
        "The boat smelled of cedar, salt, and something older that she could not place.",
        "By noon the whole town stood on the quay, pretending to mend nets they had mended twice already.",
        "Old Harun said the current would take the boat back out before the tide turned, and nobody argued.",
        "At three o'clock the wind changed, and the bells in the tower rang without anyone pulling the rope.",
        "Mira stepped into the boat, because it was the only thing in the harbour that had asked nothing of her.",
        "The water beneath the hull was perfectly still, and perfectly clear, and it showed her a sky she did not recognise.",
        "She pushed off, and the town slid backwards like a thing remembered rather than seen.",
        "Halfway out she heard the lantern keeper clear his throat, once, as if about to speak.",
        "But the fog came back first, and by the time it lifted, all four oars were gone.",
    ).mapIndexed { index, text -> ScriptLine(id = "L%02d".format(index + 1), index = index, text = text) }

    fun lineAt(index: Int): ScriptLine? = LINES.getOrNull(index)

    fun indexOf(id: String): Int = LINES.indexOfFirst { it.id == id }
}

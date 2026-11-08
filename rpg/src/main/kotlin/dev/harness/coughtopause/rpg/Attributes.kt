package dev.harness.coughtopause.rpg

enum class Attribute(val abbreviation: String) {
    STRENGTH("STR"),
    DEXTERITY("DEX"),
    CONSTITUTION("CON"),
    INTELLIGENCE("INT"),
    WISDOM("WIS"),
    CHARISMA("CHA"),
}

/** A complete set of six scores. Construction fails if any attribute is missing or out of range. */
data class AttributeScores(val values: Map<Attribute, Int>) {
    init {
        require(values.keys == Attribute.entries.toSet()) { "Every attribute needs exactly one score" }
        values.forEach { (attribute, score) ->
            require(score in MIN_SCORE..MAX_SCORE) { "$attribute score $score outside $MIN_SCORE..$MAX_SCORE" }
        }
    }

    operator fun get(attribute: Attribute): Int = values.getValue(attribute)

    fun modifier(attribute: Attribute): Int = attributeModifier(this[attribute])

    companion object {
        const val MIN_SCORE = 1

        /** Rolled scores top out at 18; the headroom to 20 is reserved for rare story gains. */
        const val MAX_SCORE = 20
    }
}

/** Conventional d20 modifier: 10–11 → +0, 12–13 → +1, 8–9 → −1, 18 → +4. */
fun attributeModifier(score: Int): Int = Math.floorDiv(score - 10, 2)

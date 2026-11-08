package dev.harness.coughtopause.rpg

import org.junit.Assert.fail

/** Plays back exact die faces: `ScriptedDice(20, 6)` makes the next d20 a 20 and the next d6 a 6. */
class ScriptedDice(vararg faces: Int) : RandomSource {
    private val queue = ArrayDeque(faces.toList())
    override fun nextInt(bound: Int): Int {
        val face = queue.removeFirstOrNull() ?: error("ScriptedDice exhausted")
        require(face in 1..bound) { "scripted face $face impossible on d$bound" }
        return face - 1
    }

    val remaining: Int get() = queue.size
}

/** Fails the test if anything rolls. */
object NoDice : RandomSource {
    override fun nextInt(bound: Int): Int = error("No roll was expected")
}

fun <T> Validated<T, *>.valid(): T = when (this) {
    is Validated.Valid -> value
    is Validated.Invalid -> {
        fail("Expected valid, got ${errors.map { it.message }}")
        throw AssertionError()
    }
}

fun Validated<*, *>.errors(): List<DomainError> = when (this) {
    is Validated.Valid -> {
        fail("Expected invalid, got $value")
        throw AssertionError()
    }
    is Validated.Invalid -> errors
}

object Fixtures {
    /** 16, 15, 14, 13, 12, 10 in roll order. */
    val goodRolls = RolledAttributeSet(
        rolls = listOf(
            AttributeRoll(listOf(6, 5, 5, 1)),
            AttributeRoll(listOf(5, 5, 5, 2)),
            AttributeRoll(listOf(5, 5, 4, 3)),
            AttributeRoll(listOf(5, 4, 4, 1)),
            AttributeRoll(listOf(4, 4, 4, 2)),
            AttributeRoll(listOf(4, 3, 3, 2)),
        ),
        rerolledSets = emptyList(),
        safeguardExhausted = false,
    )

    /** STR 16, DEX 15, CON 14, INT 13, WIS 12, CHA 10. */
    val inOrder = Attribute.entries.associateWith { it.ordinal }

    fun character(background: BackgroundId = Backgrounds.STREET_RUNNER, name: String = "Wren"): PlayerCharacter =
        CharacterFactory().create(name, goodRolls, inOrder, background).valid()

    fun campaign(
        background: BackgroundId = Backgrounds.STREET_RUNNER,
        id: String = "test-campaign",
        seed: Long = 42L,
        rules: Rules = Rules(),
    ): Campaign = rules.newCampaign(CampaignId(id), seed, character(background), FallbackRegion.proposal, 1_000L).valid()

    fun Campaign.locationNamed(name: String): Location = world.locations.values.single { it.name == name }

    fun Campaign.npcNamed(name: String): Npc = world.npcs.values.single { it.name == name }

    /** Untrusted model output, as a hosted LLM would return it. */
    val mockGeneratedRegionJson = """
        {
          "startLocation": "square",
          "locations": [
            {"key": "square", "name": "Millbrook Green", "kind": "settlement",
             "description": "A village green ringed by thatched cottages and a silent water mill.",
             "connectsTo": ["tavern", "marsh"]},
            {"key": "tavern", "name": "The Drowned Goose", "kind": "inn",
             "description": "Damp, cheerful, and always out of the good ale."},
            {"key": "marsh", "name": "Reedwater Marsh", "kind": "wilderness",
             "description": "Flat grey water, sucking mud and the croak of unseen things.",
             "connectsTo": ["sluice"], "creatures": ["giant_rat"]},
            {"key": "sluice", "name": "The Sunken Sluice House", "kind": "dungeon",
             "description": "A half-flooded stone building where the mill's water once ran.",
             "creatures": ["goblin"]}
          ],
          "npcs": [
            {"key": "miller", "name": "Edda Crane", "role": "miller",
             "description": "Flour-dusted and furious that the mill wheel has stopped.", "location": "square"},
            {"key": "barkeep", "name": "Tobin Ashe", "role": "tavern keeper",
             "description": "Talks too much, listens even more.", "location": "tavern"},
            {"key": "stranger", "name": "Sister Ilse", "role": "pilgrim",
             "description": "A travelling nun who asks very specific questions about the sluice.", "location": "tavern"}
          ],
          "facts": [
            {"subject": "sluice", "text": "The sluice gates were closed from the inside."},
            {"subject": "miller", "text": "Edda's apprentice has not been seen since the wheel stopped."}
          ]
        }
    """.trimIndent()
}

package dev.harness.coughtopause.rpg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckEngineTest {

    private val engine = CheckEngine(CheckRules(), ContentCatalog.STARTER, XpCurve())

    /** Street Runner: DEX 15 (+2), lockpicking 10 xp (level 2, +0), carries lockpicks (+2). */
    private val runner = Fixtures.character(Backgrounds.STREET_RUNNER)
    private val challenge = ChallengeKey("loc-0001", "strongbox")
    private val rollId = RollId("roll-1")
    private val actionId = ActionId("act-1")

    private fun request(
        difficulty: DifficultyTier = DifficultyTier.MODERATE,
        skill: SkillId = SkillCatalog.LOCKPICKING,
        circumstances: List<Modifier> = emptyList(),
        requirements: List<Requirement> = emptyList(),
        attribute: Attribute? = null,
    ) = CheckRequest(skill, difficulty, challenge, "open the strongbox", attribute, circumstances, requirements, RollImportance.IMPORTANT)

    private fun circumstance(value: Int, label: String = "rain") = Modifier(ModifierSource.CIRCUMSTANCE, label, value)

    @Test
    fun rollRecordShowsEveryComponent() {
        val prepared = engine.prepare(runner, request(circumstances = listOf(circumstance(1, "good light")))).valid()
        val roll = engine.resolve(prepared, ScriptedDice(7), rollId, actionId)
        assertEquals(7, roll.natural)
        assertEquals(Attribute.DEXTERITY, roll.attribute)
        assertEquals(2, roll.attributeModifier)
        assertEquals(2, roll.skillLevel)
        assertEquals(0, roll.skillBonus)
        assertEquals(
            listOf(Modifier(ModifierSource.EQUIPMENT, "Lockpicks", 2), circumstance(1, "good light")),
            roll.modifiers,
        )
        assertEquals(12, roll.total) // 7 + 2 + 0 + 2 + 1
        assertEquals(12, roll.difficultyClass)
        assertEquals(CheckOutcome.SUCCESS, roll.outcome)
        assertEquals(RollImportance.IMPORTANT, roll.importance)
        assertEquals(actionId, roll.actionId)
        assertEquals(challenge, roll.challenge)
    }

    @Test
    fun oneBelowDcFails() {
        val prepared = engine.prepare(runner, request()).valid()
        assertEquals(CheckOutcome.FAILURE, engine.resolve(prepared, ScriptedDice(7), rollId, actionId).outcome) // 7+4 = 11 < 12
    }

    @Test
    fun seededChecksAreDeterministic() {
        val prepared = engine.prepare(runner, request(DifficultyTier.HARD)).valid()
        val a = List(50) { engine.resolve(prepared, SplitMix64(it.toLong()), rollId, actionId) }
        val b = List(50) { engine.resolve(prepared, SplitMix64(it.toLong()), rollId, actionId) }
        assertEquals(a, b)
        assertTrue(a.map { it.outcome }.toSet().containsAll(listOf(CheckOutcome.SUCCESS, CheckOutcome.FAILURE)))
    }

    @Test
    fun difficultyIsFixedAtPreparationAndUsedByEveryResolution() {
        val prepared = engine.prepare(runner, request(DifficultyTier.HARD)).valid()
        assertEquals(15, prepared.difficultyClass)
        // Whatever the die shows, the DC on the record is the one fixed before rolling.
        (1..20).forEach { face ->
            val roll = engine.resolve(prepared, ScriptedDice(face), rollId, actionId)
            assertEquals(15, roll.difficultyClass)
            assertEquals(roll.total!! >= 15, roll.outcome == CheckOutcome.SUCCESS)
        }
    }

    @Test
    fun outOfBoundsModifiersAreRejectedNotClamped() {
        val tooBig = engine.prepare(runner, request(circumstances = listOf(circumstance(9, "divine favour")))).errors()
        assertTrue(tooBig.contains(CheckError.ModifierOutOfBounds("divine favour", 9, 5)))

        val tooMany = engine.prepare(runner, request(circumstances = listOf(circumstance(4, "a"), circumstance(4, "b")))).errors()
        assertTrue(tooMany.contains(CheckError.ModifierTotalOutOfBounds(ModifierSource.CIRCUMSTANCE, 8, 5)))

        val smuggledEquipment = Modifier(ModifierSource.EQUIPMENT, "legendary picks", 3)
        assertTrue(engine.prepare(runner, request(circumstances = listOf(smuggledEquipment))).errors()
            .contains(CheckError.ModifierSourceNotAllowed("legendary picks")))
    }

    @Test
    fun unknownAndUnplayableSkillsAreRejected() {
        assertEquals(
            listOf(CheckError.UnknownSkill(SkillId("dragon_taming"))),
            engine.prepare(runner, request(skill = SkillId("dragon_taming"))).errors(),
        )
        assertEquals(
            listOf(CheckError.SkillNotPlayable(SkillCatalog.MINING)),
            engine.prepare(runner, request(skill = SkillCatalog.MINING)).errors(),
        )
    }

    @Test
    fun unmetPrerequisiteMakesTheActionImpossibleWithoutRolling() {
        val needsKey = request(requirements = listOf(Requirement.HasItem(ContentCatalog.COPPER_RING)))
        val roll = engine.resolve(engine.prepare(runner, needsKey).valid(), NoDice, rollId, actionId)
        assertEquals(CheckOutcome.IMPOSSIBLE, roll.outcome)
        assertNull(roll.natural)
        assertNull(roll.total)
        assertTrue(roll.explanation, roll.explanation.contains("Copper ring"))

        val needsSkill = request(requirements = listOf(Requirement.MinimumSkillLevel(SkillCatalog.MAGIC, 5)))
        assertEquals(CheckOutcome.IMPOSSIBLE, engine.resolve(engine.prepare(runner, needsSkill).valid(), NoDice, rollId, actionId).outcome)
    }

    @Test
    fun declaredImpossibleAndTrivialNeverRoll() {
        assertEquals(CheckOutcome.IMPOSSIBLE, engine.resolve(engine.prepare(runner, request(DifficultyTier.IMPOSSIBLE)).valid(), NoDice, rollId, actionId).outcome)
        assertEquals(CheckOutcome.AUTOMATIC_SUCCESS, engine.resolve(engine.prepare(runner, request(DifficultyTier.TRIVIAL)).valid(), NoDice, rollId, actionId).outcome)
    }

    @Test
    fun outOfReachDcIsImpossibleAndCertainSuccessIsAutomatic() {
        // CHA 10 (+0), deception level 2 (+0): best possible is 20 < HEROIC 21.
        val heroic = request(DifficultyTier.HEROIC, skill = SkillCatalog.DECEPTION)
        val roll = engine.resolve(engine.prepare(runner, heroic).valid(), NoDice, rollId, actionId)
        assertEquals(CheckOutcome.IMPOSSIBLE, roll.outcome)
        assertEquals(21, roll.difficultyClass)

        // +4 lockpicking with +4 circumstances vs EASY 8: 1 + 8 ≥ 8, cannot fail.
        val easy = request(DifficultyTier.EASY, circumstances = listOf(circumstance(4, "box is rusted open")))
        assertEquals(CheckOutcome.AUTOMATIC_SUCCESS, engine.resolve(engine.prepare(runner, easy).valid(), NoDice, rollId, actionId).outcome)
    }

    @Test
    fun requestMayOverrideTheGoverningAttribute() {
        val strIntimidate = request(skill = SkillCatalog.INTIMIDATION, attribute = Attribute.STRENGTH)
        val prepared = engine.prepare(runner, strIntimidate).valid()
        assertEquals(Attribute.STRENGTH, prepared.attribute)
        assertEquals(3, prepared.attributeModifier) // STR 16
    }

    @Test
    fun equipmentBonusComesFromInventoryOnly() {
        val withoutPicks = runner.copy(inventory = runner.inventory - ContentCatalog.LOCKPICKS)
        assertTrue(engine.prepare(withoutPicks, request()).valid().equipment.isEmpty())
        assertEquals(2, engine.prepare(runner, request()).valid().equipment.single().value)
    }
}

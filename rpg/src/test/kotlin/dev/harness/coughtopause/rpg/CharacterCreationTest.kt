package dev.harness.coughtopause.rpg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterCreationTest {

    @Test
    fun eachScoreIsFourD6DroppingTheLowest() {
        assertEquals(15, AttributeRoll(listOf(6, 1, 5, 4)).score)
        assertEquals(3, AttributeRoll(listOf(1, 1, 1, 1)).score)
        assertEquals(18, AttributeRoll(listOf(6, 6, 6, 6)).score)
    }

    @Test
    fun seededRollingIsReproducible() {
        val roller = AttributeRoller()
        val a = roller.roll(SplitMix64(99L))
        val b = roller.roll(SplitMix64(99L))
        assertEquals(a, b)
        assertEquals(6, a.scores.size)
        assertTrue(a.scores.all { it in 3..18 })
        assertTrue(AttributeSafeguard().accepts(a.scores))
    }

    @Test
    fun safeguardRerollsAnExceptionallyPoorSet() {
        // First set: six 2-2-2-1 rolls = six 6s (total 36). Second set: six 6-6-5-1 = six 17s.
        val poor = IntArray(24) { if (it % 4 == 3) 1 else 2 }
        val good = IntArray(24) { when (it % 4) { 0, 1 -> 6; 2 -> 5; else -> 1 } }
        val rolled = AttributeRoller().roll(ScriptedDice(*(poor + good)))
        assertEquals(List(6) { 17 }, rolled.scores)
        assertEquals(1, rolled.rerolledSets.size)
        assertEquals(List(6) { 6 }, rolled.rerolledSets.single().map { it.score })
        assertFalse(rolled.safeguardExhausted)
    }

    @Test
    fun safeguardRejectsAHighTotalWithNoStandoutScore() {
        val safeguard = AttributeSafeguard()
        assertFalse(safeguard.accepts(listOf(13, 13, 13, 13, 13, 13))) // total 78, highest 13
        assertFalse(safeguard.accepts(listOf(18, 10, 9, 9, 9, 8))) // highest 18, total 63
        assertTrue(safeguard.accepts(listOf(14, 11, 11, 10, 10, 10))) // total 66, highest 14
    }

    @Test
    fun safeguardAlwaysTerminatesKeepingTheBestPoorSet() {
        val allOnes = IntArray(24 * 3) { 1 }.also { it[0] = 6 } // first set slightly better
        val rolled = AttributeRoller(AttributeSafeguard(maxRerolls = 2)).roll(ScriptedDice(*allOnes))
        assertTrue(rolled.safeguardExhausted)
        assertEquals(listOf(8, 3, 3, 3, 3, 3), rolled.scores)
        assertEquals(2, rolled.rerolledSets.size)
    }

    @Test
    fun playerAssignsRolledScoresToAttributes() {
        val choice = mapOf(
            Attribute.STRENGTH to 5, Attribute.DEXTERITY to 0, Attribute.CONSTITUTION to 2,
            Attribute.INTELLIGENCE to 4, Attribute.WISDOM to 3, Attribute.CHARISMA to 1,
        )
        val scores = AttributeAssignment.assign(Fixtures.goodRolls, choice).valid()
        assertEquals(10, scores[Attribute.STRENGTH])
        assertEquals(16, scores[Attribute.DEXTERITY])
        assertEquals(15, scores[Attribute.CHARISMA])
        assertEquals(3, scores.modifier(Attribute.DEXTERITY))
        assertEquals(0, scores.modifier(Attribute.STRENGTH))
    }

    @Test
    fun duplicateAndMissingAssignmentsAreRejected() {
        val duplicate = Fixtures.inOrder + (Attribute.CHARISMA to 0)
        val dupErrors = AttributeAssignment.assign(Fixtures.goodRolls, duplicate).errors()
        assertEquals(
            listOf(CreationError.ScoreUsedTwice(0, listOf(Attribute.STRENGTH, Attribute.CHARISMA))),
            dupErrors,
        )

        val missing = Fixtures.inOrder - Attribute.WISDOM
        assertEquals(
            listOf(CreationError.MissingAttribute(Attribute.WISDOM)),
            AttributeAssignment.assign(Fixtures.goodRolls, missing).errors(),
        )

        val outOfRange = Fixtures.inOrder + (Attribute.WISDOM to 6)
        assertTrue(AttributeAssignment.assign(Fixtures.goodRolls, outOfRange).errors()
            .contains(CreationError.ScoreIndexOutOfRange(Attribute.WISDOM, 6)))
    }

    @Test
    fun backgroundSeedsSkillsAndKitWithoutRestrictingAnySkill() {
        Backgrounds.all.forEach { background ->
            val pc = Fixtures.character(background.id)
            // Every catalog skill is present and starts at least at level 1.
            assertEquals(SkillCatalog.ids, pc.skillXp.keys)
            background.startingSkillXp.forEach { (skill, xp) -> assertEquals(xp, pc.skillXp[skill]) }
            assertEquals(background.startingItems, pc.inventory)
            assertEquals(background.startingGold, pc.gold)
        }

        // A Hedge Apprentice (magic/lore/insight) can still train and use Lockpicking.
        val rules = Rules()
        val campaign = Fixtures.campaign(Backgrounds.HEDGE_APPRENTICE)
        val inn = campaign.currentLocationId.value
        val request = CheckRequest(SkillCatalog.LOCKPICKING, DifficultyTier.MODERATE, ChallengeKey(inn, "cellar-door"), "pick the cellar lock")
        val result = rules.process(campaign, GameCommand.AttemptCheck(ActionId("a1"), request), ScriptedDice(15), 2_000L)
        val applied = result as RuleResult.Applied
        assertTrue(applied.campaign.player.skillXp.getValue(SkillCatalog.LOCKPICKING) > 0)
    }

    @Test
    fun backgroundsGrantEqualBudgets() {
        Backgrounds.all.forEach {
            assertEquals(3, it.startingSkillXp.size)
            assertEquals(30, it.startingSkillXp.values.sum())
            assertEquals(Backgrounds.STARTING_GOLD, it.startingGold)
        }
    }

    @Test
    fun vitalsDeriveFromAttributes() {
        val pc = Fixtures.character() // CON 14 (+2), INT 13 (+1)
        assertEquals(Vitals(14, 14, 6, 6), pc.vitals)
    }

    @Test
    fun invalidCreationInputsAreAllReported() {
        val errors = CharacterFactory()
            .create("  ", Fixtures.goodRolls, Fixtures.inOrder - Attribute.STRENGTH, BackgroundId("pirate_king"))
            .errors()
        assertTrue(errors.any { it is CreationError.InvalidName })
        assertTrue(errors.any { it is CreationError.UnknownBackground })
        assertTrue(errors.any { it is CreationError.MissingAttribute })
    }
}

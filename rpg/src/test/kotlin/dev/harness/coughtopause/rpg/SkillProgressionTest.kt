package dev.harness.coughtopause.rpg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillProgressionTest {

    private val curve = XpCurve()
    private val policy = XpAwardPolicy(XpRewards(), curve)
    private val innkeeperTopic = ChallengeKey("npc-0005", "disappearances")

    @Test
    fun levelCurveIsMonotonicAndCappedAtTwenty() {
        assertEquals(1, curve.levelFor(0))
        assertEquals(1, curve.levelFor(9))
        assertEquals(2, curve.levelFor(10))
        assertEquals(5, curve.levelFor(100))
        assertEquals(20, curve.levelFor(curve.maxXp))
        assertEquals(20, curve.levelFor(curve.maxXp * 10))
        (1 until MAX_SKILL_LEVEL).forEach { assertTrue(curve.totalXpFor(it + 1) > curve.totalXpFor(it)) }
        assertEquals(1900, curve.maxXp)
    }

    @Test
    fun skillBonusIsBoundedEvenAtLevelTwenty() {
        val rules = CheckRules()
        assertEquals(0, rules.skillBonus(1))
        assertEquals(1, rules.skillBonus(4))
        assertEquals(5, rules.skillBonus(20))
    }

    @Test
    fun catalogIsFixedAndCoversCombatMagicSocialAndExploration() {
        val categories = SkillCatalog.all.filter { it.playable }.map { it.category }.toSet()
        assertEquals(setOf(SkillCategory.COMBAT, SkillCategory.MAGIC, SkillCategory.SOCIAL, SkillCategory.EXPLORATION), categories)
        assertTrue(SkillCatalog.all.filter { it.category == SkillCategory.PROFESSION }.none { it.playable })
        assertEquals(null, SkillCatalog[SkillId("dragon_taming")])
    }

    @Test
    fun meaningfulFailureEarnsXp() {
        val d = policy.decide(SkillCatalog.PERSUASION, DifficultyTier.MODERATE, CheckOutcome.FAILURE, innkeeperTopic, 0, emptyMap())
        assertEquals(XpDecision(4, XpReason.MEANINGFUL_FAILURE), d)
    }

    @Test
    fun repeatingAnUnchangedChallengeDoesNotPayAgain() {
        val key = LedgerKey(SkillCatalog.PERSUASION, innkeeperTopic.subjectId, innkeeperTopic.topic)
        val afterFailure = mapOf(key to 4)
        // Failing again pays nothing.
        assertEquals(
            XpDecision(0, XpReason.REPEATED_CHALLENGE),
            policy.decide(SkillCatalog.PERSUASION, DifficultyTier.MODERATE, CheckOutcome.FAILURE, innkeeperTopic, 4, afterFailure),
        )
        // Succeeding later only tops up to the success amount.
        assertEquals(
            XpDecision(4, XpReason.SUCCESS),
            policy.decide(SkillCatalog.PERSUASION, DifficultyTier.MODERATE, CheckOutcome.SUCCESS, innkeeperTopic, 4, afterFailure),
        )
        // After full success nothing more.
        assertEquals(
            XpDecision(0, XpReason.REPEATED_CHALLENGE),
            policy.decide(SkillCatalog.PERSUASION, DifficultyTier.MODERATE, CheckOutcome.SUCCESS, innkeeperTopic, 8, mapOf(key to 8)),
        )
        // A different skill on the same challenge is independent.
        assertEquals(
            XpDecision(8, XpReason.SUCCESS),
            policy.decide(SkillCatalog.INTIMIDATION, DifficultyTier.MODERATE, CheckOutcome.SUCCESS, innkeeperTopic, 0, mapOf(key to 8)),
        )
    }

    @Test
    fun rephrasingTopicsOnOneSubjectHitsALimit() {
        val ledger = (1..3).associate { LedgerKey(SkillCatalog.PERSUASION, "npc-0005", "topic-$it") to 8 }
        assertEquals(
            XpDecision(0, XpReason.SUBJECT_LIMIT_REACHED),
            policy.decide(SkillCatalog.PERSUASION, DifficultyTier.MODERATE, CheckOutcome.SUCCESS, ChallengeKey("npc-0005", "topic-4"), 24, ledger),
        )
    }

    @Test
    fun noRollMeansNoXp() {
        assertEquals(XpReason.TRIVIAL, policy.decide(SkillCatalog.STEALTH, DifficultyTier.TRIVIAL, CheckOutcome.AUTOMATIC_SUCCESS, innkeeperTopic, 0, emptyMap()).reason)
        assertEquals(XpReason.NOT_ATTEMPTED, policy.decide(SkillCatalog.STEALTH, DifficultyTier.IMPOSSIBLE, CheckOutcome.IMPOSSIBLE, innkeeperTopic, 0, emptyMap()).reason)
    }

    @Test
    fun xpStopsAtLevelTwenty() {
        val nearCap = curve.maxXp - 3
        assertEquals(
            XpDecision(3, XpReason.SUCCESS),
            policy.decide(SkillCatalog.MELEE, DifficultyTier.HEROIC, CheckOutcome.SUCCESS, innkeeperTopic, nearCap, emptyMap()),
        )
        assertEquals(
            XpDecision(0, XpReason.SKILL_AT_CAP),
            policy.decide(SkillCatalog.MELEE, DifficultyTier.HEROIC, CheckOutcome.SUCCESS, innkeeperTopic, curve.maxXp, emptyMap()),
        )
    }

    @Test
    fun skillsLevelIndependentlyThroughTheRules() {
        val rules = Rules()
        var campaign = Fixtures.campaign()
        val inn = campaign.currentLocationId.value
        val meleeBefore = campaign.player.skillXp.getValue(SkillCatalog.MELEE)
        val stealthBefore = campaign.player.skillXp.getValue(SkillCatalog.STEALTH)
        val req = CheckRequest(SkillCatalog.STEALTH, DifficultyTier.HARD, ChallengeKey(inn, "slip-past-patrons"), "slip unseen to the back room")
        campaign = (rules.process(campaign, GameCommand.AttemptCheck(ActionId("s1"), req), ScriptedDice(18), 5L) as RuleResult.Applied).campaign
        assertEquals(stealthBefore + 12, campaign.player.skillXp.getValue(SkillCatalog.STEALTH))
        assertEquals(meleeBefore, campaign.player.skillXp.getValue(SkillCatalog.MELEE))
        assertEquals(2, curve.levelFor(campaign.player.skillXp.getValue(SkillCatalog.STEALTH)))
    }

    @Test
    fun xpStoredNeverExceedsCapThroughTheRules() {
        val rules = Rules()
        val base = Fixtures.campaign()
        val capped = base.copy(player = base.player.copy(skillXp = base.player.skillXp + (SkillCatalog.MELEE to curve.maxXp - 1)))
        val req = CheckRequest(SkillCatalog.MELEE, DifficultyTier.HEROIC, ChallengeKey(capped.currentLocationId.value, "arm-wrestle"), "arm-wrestle the smith",
            circumstances = listOf(Modifier(ModifierSource.CIRCUMSTANCE, "drunk opponent", 5)))
        val after = (rules.process(capped, GameCommand.AttemptCheck(ActionId("m1"), req), ScriptedDice(20), 5L) as RuleResult.Applied).campaign
        assertEquals(curve.maxXp, after.player.skillXp.getValue(SkillCatalog.MELEE))
        assertEquals(20, curve.levelFor(after.player.skillXp.getValue(SkillCatalog.MELEE)))
    }
}

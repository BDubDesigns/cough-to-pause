package dev.harness.coughtopause.rpg

import dev.harness.coughtopause.rpg.Fixtures.locationNamed
import dev.harness.coughtopause.rpg.Fixtures.npcNamed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesTest {

    private val rules = Rules()
    private val start = Fixtures.campaign()
    private val marta = start.npcNamed("Marta Vell")

    private fun persuadeMarta(id: String, difficulty: DifficultyTier = DifficultyTier.MODERATE) = GameCommand.AttemptCheck(
        ActionId(id),
        CheckRequest(
            SkillCatalog.PERSUASION, difficulty, ChallengeKey(marta.id.value, "disappearances"),
            "ask Marta whether anyone has disappeared", importance = RollImportance.IMPORTANT,
        ),
    )

    private fun applied(result: RuleResult): RuleResult.Applied {
        assertTrue("expected Applied, got $result", result is RuleResult.Applied)
        return result as RuleResult.Applied
    }

    @Test
    fun newCampaignStartsAtTheProposedStartWithCreationEvents() {
        assertEquals("The Crooked Lantern", start.world.locations.getValue(start.currentLocationId).name)
        assertEquals(listOf("CampaignStarted", "WorldContentAccepted"), start.events.map { it.payload.javaClass.simpleName })
        assertEquals(1, start.day)
        assertNull(start.encounter)
    }

    @Test
    fun checkRecordsRollAndXpAsEventsOfThatAction() {
        val result = applied(rules.process(start, persuadeMarta("a1"), ScriptedDice(3), 10L))
        // Street Runner: CHA 10 (+0), persuasion level 1 (+0): 3 vs DC 12 fails.
        val roll = (result.events[0].payload as EventPayload.CheckResolved).roll
        assertEquals(CheckOutcome.FAILURE, roll.outcome)
        assertEquals(RollImportance.IMPORTANT, roll.importance)
        val xp = result.events[1].payload as EventPayload.SkillXp
        assertEquals(EventPayload.SkillXp(SkillCatalog.PERSUASION, 4, 4, 1, 1, XpReason.MEANINGFUL_FAILURE), xp)
        assertTrue(result.events.all { it.actionId == ActionId("a1") })
        assertEquals(listOf(roll), result.campaign.rolls)
        assertEquals(result.events.map { it.id }, result.campaign.appliedActions.getValue(ActionId("a1")).eventIds)
    }

    @Test
    fun replayingAnActionIdChangesNothingAndReturnsTheOriginalEvents() {
        val first = applied(rules.process(start, persuadeMarta("a1"), ScriptedDice(15), 10L))
        val replay = rules.process(first.campaign, persuadeMarta("a1"), NoDice, 99L)
        assertEquals(RuleResult.Replayed(first.events), replay)
        assertEquals(8, first.campaign.player.skillXp.getValue(SkillCatalog.PERSUASION))
    }

    @Test
    fun reusingAnActionIdWithEasierDifficultyIsRefused() {
        val failed = applied(rules.process(start, persuadeMarta("a1", DifficultyTier.HARD), ScriptedDice(3), 10L))
        val retcon = rules.process(failed.campaign, persuadeMarta("a1", DifficultyTier.EASY), NoDice, 11L)
        assertEquals(RuleResult.Rejected(listOf(CommandError.ActionIdConflict(ActionId("a1")))), retcon)
    }

    @Test
    fun newAttemptAtSameUnchangedChallengeEarnsNoFurtherXp() {
        val first = applied(rules.process(start, persuadeMarta("a1"), ScriptedDice(3), 10L))
        val second = applied(rules.process(first.campaign, persuadeMarta("a2"), ScriptedDice(4), 11L))
        assertEquals(XpReason.REPEATED_CHALLENGE, (second.events[1].payload as EventPayload.SkillXp).reason)
        assertEquals(4, second.campaign.player.skillXp.getValue(SkillCatalog.PERSUASION))
        // The roll itself is still recorded.
        assertEquals(2, second.campaign.rolls.size)
    }

    @Test
    fun invalidCommandsLeaveTheCampaignUntouched() {
        val bad = listOf(
            GameCommand.AttemptCheck(ActionId("x1"), CheckRequest(SkillId("necromancy"), DifficultyTier.EASY, ChallengeKey(marta.id.value, "t"), "raise dead")),
            GameCommand.AttemptCheck(ActionId("x2"), CheckRequest(SkillCatalog.PERSUASION, DifficultyTier.EASY, ChallengeKey("npc-9999", "t"), "talk to a ghost")),
            GameCommand.Travel(ActionId("x3"), start.locationNamed("The Old Barrow").id),
            GameCommand.Travel(ActionId("x4"), LocationId("loc-9999")),
            GameCommand.SpendGold(ActionId("x5"), 500, "buy the inn"),
            GameCommand.SpendGold(ActionId("x6"), 0, "nothing"),
            GameCommand.StartEncounter(ActionId("x7"), listOf(ContentCatalog.GOBLIN)), // no goblins at the inn
            GameCommand.StartEncounter(ActionId("x8"), listOf(CreatureTemplateId("ancient_dragon"))),
            GameCommand.CommitWorldProposal(ActionId("x9"), WorldProposal()),
        )
        bad.forEach { command ->
            val result = rules.process(start, command, NoDice, 10L)
            assertTrue("$command should be rejected, got $result", result is RuleResult.Rejected)
        }
    }

    @Test
    fun goldIsSpentExactlyOnce() {
        val paid = applied(rules.process(start, GameCommand.SpendGold(ActionId("rest-1"), 5, "room at the inn"), NoDice, 10L))
        assertEquals(10, paid.campaign.player.gold)
        assertEquals(EventPayload.GoldSpent(5, "room at the inn", 10), paid.events.single().payload)
        val again = rules.process(paid.campaign, GameCommand.SpendGold(ActionId("rest-1"), 5, "room at the inn"), NoDice, 11L)
        assertTrue(again is RuleResult.Replayed)
        val broke = rules.process(paid.campaign, GameCommand.SpendGold(ActionId("rest-2"), 11, "room at the inn"), NoDice, 12L)
        assertEquals(RuleResult.Rejected(listOf(CommandError.InsufficientGold(11, 10))), broke)
    }

    @Test
    fun travelFollowsConnectionsOnly() {
        val village = start.locationNamed("Thornwick")
        val woods = start.locationNamed("Bramblewood")
        val atVillage = applied(rules.process(start, GameCommand.Travel(ActionId("t1"), village.id), NoDice, 10L)).campaign
        val atWoods = applied(rules.process(atVillage, GameCommand.Travel(ActionId("t2"), woods.id), NoDice, 11L)).campaign
        assertEquals(woods.id, atWoods.currentLocationId)
        assertEquals(
            RuleResult.Rejected(listOf(CommandError.AlreadyThere)),
            rules.process(atWoods, GameCommand.Travel(ActionId("t3"), woods.id), NoDice, 12L),
        )
    }

    @Test
    fun encounterUsesCatalogCreaturesAndWaitsForThePlayer() {
        val barrow = start.locationNamed("The Old Barrow")
        val there = start.copy(currentLocationId = barrow.id)
        val result = applied(rules.process(there, GameCommand.StartEncounter(ActionId("e1"), listOf(ContentCatalog.GOBLIN, ContentCatalog.GOBLIN)), NoDice, 10L))
        val encounter = result.campaign.encounter!!
        assertEquals(EncounterStatus.ACTIVE, encounter.status)
        assertEquals(TurnState(TurnState.PLAYER, awaitingPlayerDecision = true), encounter.turn)
        assertEquals(listOf(7, 7), encounter.enemies.map { it.hp })
        assertEquals(2, encounter.enemies.map { it.id }.toSet().size)

        // Legal-action gating: no walking away mid-fight, no second encounter.
        assertEquals(
            RuleResult.Rejected(listOf(CommandError.EncounterInProgress)),
            rules.process(result.campaign, GameCommand.Travel(ActionId("e2"), start.locationNamed("Bramblewood").id), NoDice, 11L),
        )
        // A creature instance is a valid challenge subject. The check is the player's action for the
        // round, so both goblins answer it (two misses on a 1).
        val sizeUp = GameCommand.AttemptCheck(
            ActionId("e3"),
            CheckRequest(SkillCatalog.INSIGHT, DifficultyTier.MODERATE, ChallengeKey(encounter.enemies[0].id, "read-intent"), "read the goblin"),
        )
        assertTrue(rules.process(result.campaign, sizeUp, ScriptedDice(10, 1, 1), 12L) is RuleResult.Applied)
    }
}

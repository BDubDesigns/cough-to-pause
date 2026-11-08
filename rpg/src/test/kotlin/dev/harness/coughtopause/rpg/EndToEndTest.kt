package dev.harness.coughtopause.rpg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The PR's definition of done, with no LLM, network or UI: create a character, initialise a
 * campaign, resolve a check, award independent skill XP, record it, save, restart, read back the
 * exact same state — and show that replaying the action id cannot duplicate the reward.
 */
class EndToEndTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun firstSessionSurvivesRestartAndReplay() {
        val dir = tmp.newFolder("campaigns")
        val id = CampaignId("first-adventure")
        var clock = 1_700_000_000_000L

        // 1. Character creation.
        val rolled = AttributeRoller().roll(SplitMix64(2026L))
        val byScore = rolled.scores.withIndex().sortedByDescending { it.value }.map { it.index }
        val assignment = listOf(
            Attribute.DEXTERITY, Attribute.CHARISMA, Attribute.WISDOM,
            Attribute.CONSTITUTION, Attribute.INTELLIGENCE, Attribute.STRENGTH,
        ).zip(byScore).toMap()
        val hero = CharacterFactory().create("Wren", rolled, assignment, Backgrounds.WANDERING_MINSTREL).valid()

        // 2. Campaign from a (mocked) generated region.
        val region = WorldProposalParser.parse(Fixtures.mockGeneratedRegionJson).valid()
        val processor = ActionProcessor(FileCampaignStore(dir), Rules(), clock = { clock++ })
        val created = processor.createCampaign(id, seed = 0xC0FFEEL, character = hero, region = region)
        val campaign = (created as CreateCampaignResult.Created).campaign
        val barkeep = campaign.world.npcs.values.single { it.name == "Tobin Ashe" }

        // 3. A meaningful social check, then an independent exploration check.
        val ask = GameCommand.AttemptCheck(
            ActionId("turn-0001"),
            CheckRequest(
                SkillCatalog.PERSUASION, DifficultyTier.MODERATE, ChallengeKey(barkeep.id.value, "missing-apprentice"),
                "ask Tobin whether anyone has disappeared", importance = RollImportance.IMPORTANT,
            ),
        )
        val look = GameCommand.AttemptCheck(
            ActionId("turn-0002"),
            CheckRequest(
                SkillCatalog.PERCEPTION, DifficultyTier.EASY, ChallengeKey(campaign.currentLocationId.value, "muddy-boots"),
                "notice who came in from the marsh",
            ),
        )
        val askResult = processor.submit(id, ask) as Submission.Committed
        processor.submit(id, look) as Submission.Committed

        val saved = (processor.load(id) as LoadResult.Loaded).campaign
        val persuasionXp = saved.player.skillXp.getValue(SkillCatalog.PERSUASION)
        assertTrue(persuasionXp > Backgrounds[Backgrounds.WANDERING_MINSTREL]!!.startingSkillXp.getValue(SkillCatalog.PERSUASION))
        assertEquals(2, saved.rolls.size)
        assertEquals(listOf(RollImportance.IMPORTANT, RollImportance.ROUTINE), saved.rolls.map { it.importance })

        // 4. Restart: new store, new processor, same directory.
        val restarted = ActionProcessor(FileCampaignStore(dir), Rules(), clock = { clock++ })
        val reloaded = (restarted.load(id) as LoadResult.Loaded).campaign
        assertEquals(saved, reloaded)

        // 5. The narrator replays the interrupted line; the orchestrator resubmits turn-0001.
        val replay = restarted.submit(id, ask)
        assertEquals(Submission.Replayed(askResult.events), replay)
        val afterReplay = (restarted.load(id) as LoadResult.Loaded).campaign
        assertEquals(saved, afterReplay)
        assertEquals(persuasionXp, afterReplay.player.skillXp.getValue(SkillCatalog.PERSUASION))
    }
}

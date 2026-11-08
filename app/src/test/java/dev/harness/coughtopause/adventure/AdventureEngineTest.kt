package dev.harness.coughtopause.adventure

import dev.harness.coughtopause.rpg.ActionId
import dev.harness.coughtopause.rpg.Backgrounds
import dev.harness.coughtopause.rpg.Campaign
import dev.harness.coughtopause.rpg.CampaignId
import dev.harness.coughtopause.rpg.ChallengeKey
import dev.harness.coughtopause.rpg.CheckRequest
import dev.harness.coughtopause.rpg.CreateCampaignResult
import dev.harness.coughtopause.rpg.DifficultyTier
import dev.harness.coughtopause.rpg.EventPayload
import dev.harness.coughtopause.rpg.GameCommand
import dev.harness.coughtopause.rpg.RollImportance
import dev.harness.coughtopause.rpg.SkillCatalog
import dev.harness.coughtopause.rpg.Submission
import dev.harness.coughtopause.rpg.Validated
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * End-to-end slice tests against the real engine: character → fallback region → command →
 * replay → reload from disk. No Android types are involved, so this is the same code the app runs.
 */
class AdventureEngineTest {

    private fun engine(): AdventureEngine {
        val dir = File("build/tmp/adventure-test-${System.nanoTime()}")
        return AdventureEngine(dir, clock = { 1_000L })
    }

    private fun newCampaign(engine: AdventureEngine): Campaign {
        val rolled = engine.rollAttributes(42L)
        val assignment = engine.autoAssign(rolled, Backgrounds.CARAVAN_GUARD)
        val character = engine.createCharacter("Mira", rolled, assignment, Backgrounds.CARAVAN_GUARD)
        assertTrue("character rejected: $character", character is Validated.Valid)
        val created = engine.createCampaign(
            CampaignId("c-test"),
            42L,
            (character as Validated.Valid).value,
        )
        assertTrue("campaign rejected: $created", created is CreateCampaignResult.Created)
        return (created as CreateCampaignResult.Created).campaign
    }

    private fun searchCheck(campaign: Campaign, actionId: String): GameCommand.AttemptCheck =
        GameCommand.AttemptCheck(
            ActionId(actionId),
            CheckRequest(
                skill = SkillCatalog.PERCEPTION,
                difficulty = DifficultyTier.MODERATE,
                challenge = ChallengeKey(campaign.currentLocationId.value, "search-room"),
                intent = "search the room",
                importance = RollImportance.IMPORTANT,
            ),
        )

    @Test
    fun createsACharacterInTheFallbackWorld() {
        val engine = engine()
        val campaign = newCampaign(engine)
        val location = campaign.world.locations[campaign.currentLocationId]
        assertNotNull("starting location missing", location)
        assertTrue("starting location needs a name", location!!.name.isNotBlank())
        assertTrue("world should have several locations", campaign.world.locations.size >= 3)
        assertTrue("character should have attributes", campaign.player.attributes.values.size == 6)
    }

    @Test
    fun checkIsResolvedByTheEngineAndRecordedAsARoll() {
        val engine = engine()
        val campaign = newCampaign(engine)
        val result = engine.submit(campaign.id, searchCheck(campaign, "a-search"))
        assertTrue("expected Committed, got $result", result is Submission.Committed)

        val reloaded = engine.load(campaign.id)!!
        val roll = reloaded.rolls.lastOrNull()
        assertNotNull("no roll recorded", roll)
        assertEquals("perception", roll!!.skill.value)
        assertNotNull("d20 result should be recorded", roll.natural)
        assertTrue(
            "roll should be in the event log",
            reloaded.events.any { it.payload is EventPayload.CheckResolved },
        )
    }

    @Test
    fun resubmittingTheSameActionIdReplaysWithoutChangingState() {
        val engine = engine()
        val campaign = newCampaign(engine)
        val command = searchCheck(campaign, "a-replay")

        val first = engine.submit(campaign.id, command)
        assertTrue(first is Submission.Committed)
        val afterFirst = engine.load(campaign.id)!!

        val second = engine.submit(campaign.id, command)
        assertTrue("retry must replay, not re-roll", second is Submission.Replayed)
        assertEquals(
            "replay must return the original events",
            (first as Submission.Committed).events.map { it.id },
            (second as Submission.Replayed).events.map { it.id },
        )
        val afterSecond = engine.load(campaign.id)!!
        assertEquals(afterFirst.events.size, afterSecond.events.size)
        assertEquals(afterFirst.appliedActions.size, afterSecond.appliedActions.size)
    }

    @Test
    fun campaignSurvivesANewEngineOverTheSameDirectory() {
        val dir = File("build/tmp/adventure-persist-${System.nanoTime()}")
        val engine = AdventureEngine(dir, clock = { 1_000L })
        val campaign = newCampaign(engine)
        engine.submit(campaign.id, searchCheck(campaign, "a-persist"))

        val reopened = AdventureEngine(dir, clock = { 2_000L })
        val loaded = reopened.load(campaign.id)
        assertNotNull("campaign did not survive reopen", loaded)
        assertEquals(campaign.player.name, loaded!!.player.name)
        assertTrue("event log lost", loaded.events.size > campaign.events.size)
    }

    @Test
    fun snapshotExposesLocationNpcsExitsAndCharacterSheet() {
        val engine = engine()
        val campaign = newCampaign(engine)
        val snapshot = engine.snapshot(campaign, listOf("> look"), "test narrator")

        assertEquals(
            campaign.world.locations[campaign.currentLocationId]!!.name,
            snapshot.locationName,
        )
        assertTrue(snapshot.locationDescription.isNotBlank())
        assertTrue("expected exits from the village", snapshot.exits.isNotEmpty())
        assertTrue("expected NPCs in the starting village", snapshot.npcs.isNotEmpty())
        assertEquals(6, snapshot.attributes.size)
        assertEquals("c-test", snapshot.campaignId)
        assertTrue(snapshot.transcript.contains("> look"))
    }

    @Test
    fun travelMovesThePlayerAlongAnExistingConnection() {
        val engine = engine()
        val campaign = newCampaign(engine)
        val exit = campaign.world.locations[campaign.currentLocationId]!!.connections.first()
        val result = engine.submit(campaign.id, GameCommand.Travel(ActionId("a-travel"), exit))
        assertTrue("travel rejected: $result", result is Submission.Committed)

        val moved = engine.load(campaign.id)!!
        assertEquals(exit, moved.currentLocationId)
        assertTrue(moved.events.any { it.payload is EventPayload.Travelled })
    }
}

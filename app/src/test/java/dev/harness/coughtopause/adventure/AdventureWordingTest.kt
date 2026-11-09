package dev.harness.coughtopause.adventure

import dev.harness.coughtopause.rpg.ActionId
import dev.harness.coughtopause.rpg.Backgrounds
import dev.harness.coughtopause.rpg.Campaign
import dev.harness.coughtopause.rpg.CampaignId
import dev.harness.coughtopause.rpg.CreateCampaignResult
import dev.harness.coughtopause.rpg.EventId
import dev.harness.coughtopause.rpg.EventPayload
import dev.harness.coughtopause.rpg.GameEvent
import dev.harness.coughtopause.rpg.NpcId
import dev.harness.coughtopause.rpg.SkillCatalog
import dev.harness.coughtopause.rpg.Validated
import dev.harness.coughtopause.rpg.XpReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Wording rules the player can feel: the room is described when it is news, XP does not announce a
 * level-up that did not happen, and a slightly mispronounced name still reaches the right person.
 */
class AdventureWordingTest {

    private fun engine() = AdventureEngine(File("build/tmp/wording-${System.nanoTime()}"), clock = { 1_000L })

    private fun campaign(engine: AdventureEngine): Campaign {
        val rolled = engine.rollAttributes(9L)
        val assignment = engine.autoAssign(rolled, Backgrounds.CARAVAN_GUARD)
        val character = engine.createCharacter("Wren", rolled, assignment, Backgrounds.CARAVAN_GUARD)
        return (engine.createCampaign(
            CampaignId("c-wording"),
            9L,
            (character as Validated.Valid).value,
        ) as CreateCampaignResult.Created).campaign
    }

    private fun event(payload: EventPayload) =
        GameEvent(EventId("e1"), 0, ActionId("a1"), 1_000L, payload)

    // ---------------------------------------------------------------- the room

    @Test
    fun theRoomIsOnlyDescribedWhenItIsNews() {
        val narrator = DeterministicNarrator()
        val campaign = campaign(engine())
        val location = campaign.world.locations.getValue(campaign.currentLocationId)

        val arriving = narrator.narrateScene(campaign, emptyList(), preferLocationFirst = true, includePlace = true)
        assertTrue("arriving describes the place", arriving!!.any { it.contains(location.name) })

        val outcomeOnly = narrator.narrateScene(
            campaign,
            listOf(event(EventPayload.Travelled(campaign.currentLocationId, campaign.currentLocationId))),
            preferLocationFirst = false,
            includePlace = false,
        )
        assertFalse(
            "a repeat action must not re-read the room",
            outcomeOnly!!.any { it.contains(location.description) },
        )
    }

    // ---------------------------------------------------------------- XP wording

    @Test
    fun xpLinesDoNotAnnounceALevelUpThatDidNotHappen() {
        val narrator = DeterministicNarrator()
        val campaign = campaign(engine())
        val flat = EventPayload.SkillXp(
            skill = SkillCatalog.PERSUASION,
            amount = 4,
            totalXp = 4,
            levelBefore = 1,
            levelAfter = 1,
            reason = XpReason.SUCCESS,
        )
        val line = narrator.describeOutcome(campaign, listOf(event(flat)))!!.single()
        assertFalse("no 'level 1 to 1' noise", line.contains("level 1 to 1"))
        assertTrue(line.contains("+4 XP"))

        val levelUp = flat.copy(totalXp = 12, levelAfter = 2)
        assertTrue(
            "a real level-up is still announced",
            narrator.describeOutcome(campaign, listOf(event(levelUp)))!!.single().contains("level 1 to 2"),
        )
    }

    // ---------------------------------------------------------------- names

    @Test
    fun aNameOneLetterOffStillReachesTheRightPerson() {
        val world = WorldView(
            locationId = "inn",
            locations = emptyList(),
            npcs = listOf(
                NpcView(NpcId("marta").value, "Marta Vell", topics = listOf("barrow" to "the barrow")),
                NpcView(NpcId("hob").value, "Old Hob"),
            ),
        )
        val intent = IntentInterpreter().interpret("ask mara about the barrow", world)

        assertTrue("a one-letter slip must still resolve", intent is AdventureIntent.Ask)
        assertEquals("Marta Vell", (intent as AdventureIntent.Ask).name)
        assertEquals("barrow", intent.topicKey)
    }

    @Test
    fun aDifferentNameIsNotGuessed() {
        val world = WorldView(
            locationId = "inn",
            locations = emptyList(),
            npcs = listOf(
                NpcView(NpcId("marta").value, "Marta Vell"),
                NpcView(NpcId("hob").value, "Old Hob"),
            ),
        )
        // Two people present, and "Silas" is nobody: do not pick one at random.
        val intent = IntentInterpreter().interpret("ask silas about the barrow", world)
        assertFalse(intent is AdventureIntent.Ask)
    }
}

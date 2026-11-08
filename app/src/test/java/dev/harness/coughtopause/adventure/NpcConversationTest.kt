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
import dev.harness.coughtopause.rpg.ProposedFact
import dev.harness.coughtopause.rpg.RollImportance
import dev.harness.coughtopause.rpg.SkillCatalog
import dev.harness.coughtopause.rpg.Submission
import dev.harness.coughtopause.rpg.Validated
import dev.harness.coughtopause.rpg.WorldProposal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Conversation as a *mechanism*: a check decides whether an NPC reveals an authored fact, and the
 * fact only enters the world through the engine's proposal path.
 */
class NpcConversationTest {

    private fun engine(): AdventureEngine =
        AdventureEngine(File("build/tmp/npc-test-${System.nanoTime()}"), clock = { 1_000L })

    private fun campaign(engine: AdventureEngine): Campaign {
        val rolled = engine.rollAttributes(9L)
        val assignment = engine.autoAssign(rolled, Backgrounds.CARAVAN_GUARD)
        val character = engine.createCharacter("Mira", rolled, assignment, Backgrounds.CARAVAN_GUARD)
        return (engine.createCampaign(
            CampaignId("c-npc"),
            9L,
            (character as Validated.Valid).value,
        ) as CreateCampaignResult.Created).campaign
    }

    private fun ask(engine: AdventureEngine, campaign: Campaign, npcName: String, topic: String): Pair<Submission, Campaign> {
        val npc = campaign.world.npcs.values.first { it.name.equals(npcName, ignoreCase = true) }
        val topicSpec = NpcTopics.forNpc(npc).first { it.key == topic }
        val result = engine.submit(
            campaign.id,
            GameCommand.AttemptCheck(
                ActionId("a-ask-$topic-${System.nanoTime()}"),
                CheckRequest(
                    skill = topicSpec.skill,
                    difficulty = DifficultyTier.MODERATE,
                    challenge = ChallengeKey(npc.id.value, "topic-$topic"),
                    intent = "ask ${npc.name} about ${topicSpec.label}",
                    importance = RollImportance.IMPORTANT,
                ),
            ),
        )
        return result to (engine.load(campaign.id) ?: campaign)
    }

    @Test
    fun everyNpcInTheFallbackWorldHasTopics() {
        val engine = engine()
        val world = campaign(engine)
        assertTrue(world.world.npcs.isNotEmpty())
        world.world.npcs.values.forEach { npc ->
            assertTrue("${npc.name} has no topics", NpcTopics.forNpc(npc).isNotEmpty())
        }
    }

    @Test
    fun topicsAreFoundByLabelAndByKey() {
        val engine = engine()
        val world = campaign(engine)
        val marta = world.world.npcs.values.first { it.name == "Marta Vell" }
        assertNotNull(NpcTopics.find(marta, "ask about the barrow"))
        assertNotNull(NpcTopics.find(marta, "tell me about herself"))
        assertNull(NpcTopics.find(marta, "ask about the weather"))
    }

    @Test
    fun aSuccessfulCheckRevealsTheFactThroughTheEngine() {
        val engine = engine()
        var world = campaign(engine)
        val marta = world.world.npcs.values.first { it.name == "Marta Vell" }
        val topic = NpcTopics.forNpc(marta).first { it.key == "barrow" }

        // Roll until the check succeeds, so the test does not depend on a lucky seed.
        var success = false
        var events = emptyList<dev.harness.coughtopause.rpg.GameEvent>()
        repeat(12) {
            if (success) return@repeat
            val (result, loaded) = ask(engine, world, "Marta Vell", "barrow")
            if (result is Submission.Committed) {
                val outcome = result.events
                    .mapNotNull { (it.payload as? EventPayload.CheckResolved)?.roll?.outcome }
                    .lastOrNull()
                if (outcome == dev.harness.coughtopause.rpg.CheckOutcome.SUCCESS ||
                    outcome == dev.harness.coughtopause.rpg.CheckOutcome.AUTOMATIC_SUCCESS
                ) {
                    success = true
                    val commit = engine.submit(
                        world.id,
                        GameCommand.CommitWorldProposal(
                            ActionId("a-fact-${System.nanoTime()}"),
                            WorldProposal(facts = listOf(ProposedFact(marta.id.value, topic.fact))),
                        ),
                    )
                    assertTrue("fact commit rejected: $commit", commit is Submission.Committed)
                    events = (commit as Submission.Committed).events
                }
            }
            world = loaded
        }
        assertTrue("no successful check in 12 attempts", success)

        val after = engine.load(world.id)!!
        assertTrue(
            "the revealed fact must be committed to the world",
            after.world.facts.any { it.text == topic.fact },
        )
        assertTrue(
            "committing a fact must be recorded as an event",
            events.any { it.payload is EventPayload.WorldContentAccepted },
        )
    }

    @Test
    fun theRevealedFactIsNarratedFromCommittedWorldState() {
        val engine = engine()
        val world = campaign(engine)
        val marta = world.world.npcs.values.first { it.name == "Marta Vell" }
        val topic = NpcTopics.forNpc(marta).first()
        val commit = engine.submit(
            world.id,
            GameCommand.CommitWorldProposal(
                ActionId("a-narrate"),
                WorldProposal(facts = listOf(ProposedFact(marta.id.value, topic.fact))),
            ),
        ) as Submission.Committed
        val loaded = engine.load(world.id)!!

        val lines = NarrationScriptBuilder.build(
            loaded,
            commit.events,
            DeterministicNarrator(),
            preferLocationFirst = false,
        )
        assertTrue(
            "narration must include the revealed fact, got ${lines.map { it.text }}",
            lines.any { it.text.contains(topic.fact.take(30)) },
        )
    }

    @Test
    fun factsAreImmutableAndCannotBeDuplicated() {
        val engine = engine()
        val world = campaign(engine)
        val marta = world.world.npcs.values.first { it.name == "Marta Vell" }
        val fact = NpcTopics.forNpc(marta).first().fact

        val first = engine.submit(
            world.id,
            GameCommand.CommitWorldProposal(
                ActionId("a-dup-1"),
                WorldProposal(facts = listOf(ProposedFact(marta.id.value, fact))),
            ),
        )
        assertTrue(first is Submission.Committed)
        val afterFirst = engine.load(world.id)!!
        val count = afterFirst.world.facts.count { it.text == fact }

        // Replaying the same action id must not add a second copy.
        val replay = engine.submit(
            world.id,
            GameCommand.CommitWorldProposal(
                ActionId("a-dup-1"),
                WorldProposal(facts = listOf(ProposedFact(marta.id.value, fact))),
            ),
        )
        assertTrue(replay is Submission.Replayed)
        assertEquals(count, engine.load(world.id)!!.world.facts.count { it.text == fact })
    }

    @Test
    fun rollHistoryIsCompleteAndNewestFirst() {
        val engine = engine()
        var world = campaign(engine)
        // Three separate checks, each with its own action id.
        repeat(3) { i ->
            world = (engine.submit(
                world.id,
                GameCommand.AttemptCheck(
                    ActionId("a-history-$i"),
                    CheckRequest(
                        skill = SkillCatalog.PERCEPTION,
                        difficulty = DifficultyTier.MODERATE,
                        challenge = ChallengeKey(world.currentLocationId.value, "history-$i"),
                        intent = "look again $i",
                        importance = RollImportance.IMPORTANT,
                    ),
                ),
            ).let { engine.load(world.id) ?: world })
        }
        val snapshot = engine.snapshot(world, emptyList(), "test")
        assertEquals("every roll must be in the history", 3, snapshot.rolls.size)
        assertEquals("newest first", snapshot.rolls.first(), snapshot.lastRoll)
        assertEquals(
            "history must match the engine's roll log",
            world.rolls.map { it.natural }.reversed(),
            snapshot.rolls.map { it.natural },
        )
    }

    @Test
    fun factsTheWorldAlreadySeedsCanStillBeSpoken() {
        val engine = engine()
        val world = campaign(engine)
        val seeded = world.world.facts.first().text

        val lines = NarrationScriptBuilder.build(
            world,
            emptyList(),
            DeterministicNarrator(),
            preferLocationFirst = false,
            extraLines = listOf(seeded),
        )
        assertTrue(
            "the seeded fact must reach the narration, got ${lines.map { it.text }}",
            lines.any { it.text == seeded },
        )
        assertTrue("extra lines must respect the cap", lines.size <= NarrationScriptBuilder.MAX_LINES)
    }
}

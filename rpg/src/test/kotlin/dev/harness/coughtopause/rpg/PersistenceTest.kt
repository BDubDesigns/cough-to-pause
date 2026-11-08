package dev.harness.coughtopause.rpg

import dev.harness.coughtopause.rpg.Fixtures.locationNamed
import dev.harness.coughtopause.rpg.Fixtures.npcNamed
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class PersistenceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val id = CampaignId("save-test")
    private var now = 1_000L

    private fun processor(store: CampaignStore) = ActionProcessor(store, Rules(), clock = { now++ })

    private fun newGame(store: CampaignStore): Campaign {
        val created = processor(store).createCampaign(id, 777L, Fixtures.character(), FallbackRegion.proposal)
        return (created as CreateCampaignResult.Created).campaign
    }

    private fun loaded(store: CampaignStore): Campaign = (store.load(id) as LoadResult.Loaded).campaign

    private fun persuade(campaign: Campaign, actionId: String) = GameCommand.AttemptCheck(
        ActionId(actionId),
        CheckRequest(
            SkillCatalog.PERSUASION, DifficultyTier.MODERATE,
            ChallengeKey(campaign.npcNamed("Marta Vell").id.value, "disappearances"), "ask about disappearances",
        ),
    )

    /** A campaign exercising every persisted type: rolls, XP, ledger, gold, travel, encounter, facts. */
    private fun richCampaign(): Campaign {
        val rules = Rules()
        var c = Fixtures.campaign()
        fun step(command: GameCommand, dice: RandomSource = NoDice) {
            c = (rules.process(c, command, dice, 50L + c.events.size) as RuleResult.Applied).campaign
        }
        step(persuade(c, "p1"), ScriptedDice(19))
        step(GameCommand.SpendGold(ActionId("g1"), 3, "a round for the room"))
        step(GameCommand.Travel(ActionId("t1"), c.locationNamed("Thornwick").id))
        step(GameCommand.Travel(ActionId("t2"), c.locationNamed("Bramblewood").id))
        step(GameCommand.StartEncounter(ActionId("e1"), listOf(ContentCatalog.GIANT_RAT)))
        step(
            GameCommand.AttemptCheck(
                ActionId("c1"),
                CheckRequest(
                    SkillCatalog.LOCKPICKING, DifficultyTier.HARD, ChallengeKey(c.currentLocationId.value, "trapper-chest"),
                    "open the trapper's chest", circumstances = listOf(Modifier(ModifierSource.CIRCUMSTANCE, "rusted hinge", -2)),
                    requirements = listOf(Requirement.HasItem(ContentCatalog.LOCKPICKS)), importance = RollImportance.ROUTINE,
                ),
            ),
            ScriptedDice(11),
        )
        return c
    }

    @Test
    fun campaignRoundTripsExactly() {
        val campaign = richCampaign()
        val decoded = CampaignCodec.decode(CampaignCodec.encode(campaign)).valid()
        assertEquals(campaign, decoded)
        assertEquals(campaign.rolls, decoded.rolls)
        // Encoding is stable, so re-saving an unchanged campaign is a no-op on disk.
        assertEquals(CampaignCodec.encode(campaign), CampaignCodec.encode(decoded))
    }

    @Test
    fun saveRestartLoadGivesIdenticalState() {
        val dir = tmp.newFolder("campaigns")
        val campaign = richCampaign()
        FileCampaignStore(dir).save(campaign)
        // "Restart": brand-new store and processor over the same directory.
        val reloaded = (FileCampaignStore(dir).load(campaign.id) as LoadResult.Loaded).campaign
        assertEquals(campaign, reloaded)
        assertEquals(listOf(campaign.id), FileCampaignStore(dir).list())
    }

    @Test
    fun futureSchemaIsRefusedAndCorruptDataIsReported() {
        val future = CampaignCodec.encode(richCampaign()).replaceFirst("\"schema\":1", "\"schema\":99")
        assertEquals(listOf(PersistenceError.UnsupportedSchema(99, 1)), CampaignCodec.decode(future).errors())
        assertTrue(CampaignCodec.decode("{not json").errors().single() is PersistenceError.Corrupt)
        assertTrue(CampaignCodec.decode("""{"schema":1}""").errors().single() is PersistenceError.Corrupt)
        val truncated = CampaignCodec.encode(richCampaign()).let { it.substring(0, it.length / 2) }
        assertTrue(CampaignCodec.decode(truncated).errors().single() is PersistenceError.Corrupt)
    }

    @Test
    fun integrityViolationsAreCorruptNotSilentlyAccepted() {
        val json = JSONObject(CampaignCodec.encode(richCampaign()))
        json.getJSONArray("events").getJSONObject(1).put("sequence", 7)
        assertTrue(CampaignCodec.decode(json.toString()).errors().single() is PersistenceError.Corrupt)

        val dangling = JSONObject(CampaignCodec.encode(richCampaign())).put("currentLocation", "loc-9999")
        assertTrue(CampaignCodec.decode(dangling.toString()).errors().single() is PersistenceError.Corrupt)
    }

    @Test
    fun committedActionIsNotRepeatedAfterRestart() {
        val dir = tmp.newFolder("campaigns")
        val campaign = newGame(FileCampaignStore(dir))
        val command = persuade(campaign, "turn-1")

        val first = processor(FileCampaignStore(dir)).submit(id, command)
        assertTrue(first is Submission.Committed)
        val afterFirst = loaded(FileCampaignStore(dir))

        // App restarts; the orchestrator replays the narration line and resubmits the same action.
        val replay = processor(FileCampaignStore(dir)).submit(id, command)
        assertEquals(Submission.Replayed((first as Submission.Committed).events), replay)
        assertEquals(afterFirst, loaded(FileCampaignStore(dir)))
        assertEquals(1, afterFirst.rolls.size)
    }

    @Test
    fun crashBeforeCommitLeavesPreviousSaveAndRetryAppliesOnce() {
        val dir = tmp.newFolder("campaigns")
        val before = newGame(FileCampaignStore(dir))
        val saveFile = File(dir, "${id.value}.json")
        val bytesBefore = saveFile.readBytes()
        val command = GameCommand.SpendGold(ActionId("pay-inn"), 5, "room at the inn")

        // Process dies after writing the temp file but before the atomic rename.
        val crashing = FileCampaignStore(dir) { throw IOException("simulated power loss") }
        try {
            processor(crashing).submit(id, command)
            throw AssertionError("expected simulated crash")
        } catch (expected: IOException) {
        }
        assertArrayEquals(bytesBefore, saveFile.readBytes())
        assertEquals(before, loaded(FileCampaignStore(dir)))

        // Restart and retry the same action: applied exactly once.
        val retry = processor(FileCampaignStore(dir)).submit(id, command)
        assertTrue(retry is Submission.Committed)
        val again = processor(FileCampaignStore(dir)).submit(id, command)
        assertTrue(again is Submission.Replayed)
        val after = loaded(FileCampaignStore(dir))
        assertEquals(before.player.gold - 5, after.player.gold)
        assertEquals(1, after.events.count { it.payload is EventPayload.GoldSpent })
        assertFalse(File(dir, "${id.value}.json.tmp").exists())
    }

    @Test
    fun retryAfterCrashRollsTheSameDice() {
        val dir = tmp.newFolder("campaigns")
        val campaign = newGame(FileCampaignStore(dir))
        val command = persuade(campaign, "turn-1")
        try {
            processor(FileCampaignStore(dir) { throw IOException("crash") }).submit(id, command)
        } catch (expected: IOException) {
        }
        val leftoverTemp = File(dir, "${id.value}.json.tmp")
        assertTrue(leftoverTemp.exists())
        val crashedAttempt = CampaignCodec.decode(leftoverTemp.readText()).valid().rolls.single()

        processor(FileCampaignStore(dir)).submit(id, command)
        val committed = loaded(FileCampaignStore(dir)).rolls.single()
        // Killing the app mid-commit cannot be used to reroll a bad result.
        assertEquals(crashedAttempt.natural, committed.natural)
        assertEquals(crashedAttempt.outcome, committed.outcome)
    }

    @Test
    fun rejectedCommandDoesNotTouchTheSaveFile() {
        val dir = tmp.newFolder("campaigns")
        newGame(FileCampaignStore(dir))
        val saveFile = File(dir, "${id.value}.json")
        val bytes = saveFile.readBytes()
        val result = processor(FileCampaignStore(dir)).submit(id, GameCommand.SpendGold(ActionId("x"), 999, "castle"))
        assertTrue(result is Submission.Rejected)
        assertArrayEquals(bytes, saveFile.readBytes())
    }

    @Test
    fun campaignsCannotBeCreatedTwiceAndUnreadableSavesAreNotOverwritten() {
        val dir = tmp.newFolder("campaigns")
        newGame(FileCampaignStore(dir))
        assertEquals(
            CreateCampaignResult.AlreadyExists,
            processor(FileCampaignStore(dir)).createCampaign(id, 1L, Fixtures.character(), FallbackRegion.proposal),
        )
        val saveFile = File(dir, "${id.value}.json")
        saveFile.writeText("garbage")
        val result = processor(FileCampaignStore(dir)).submit(id, GameCommand.SpendGold(ActionId("g"), 1, "tip"))
        assertTrue(result is Submission.Unavailable)
        assertEquals("garbage", saveFile.readText())
    }
}

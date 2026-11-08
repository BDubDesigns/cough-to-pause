package dev.harness.coughtopause.adventure

import dev.harness.coughtopause.rpg.DifficultyTier
import dev.harness.coughtopause.rpg.SkillCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentInterpreterTest {

    private val interpreter = IntentInterpreter()
    private val world = WorldView(
        locationId = "village",
        locations = listOf("village" to "Thornwick", "inn" to "The Drowned Lantern"),
        npcs = listOf(
            NpcView("npc-1", "Mira", listOf("help" to "helping her")),
            NpcView("npc-2", "Harun", listOf("road" to "the coast road")),
        ),
    )

    @Test
    fun lookIsRecognised() {
        assertEquals(AdventureIntent.Look, interpreter.interpret("look around", world))
        assertEquals(AdventureIntent.Look, interpreter.interpret("where am I?", world))
    }

    @Test
    fun travelResolvesAKnownPlaceByName() {
        val intent = interpreter.interpret("go to the drowned lantern", world)
        assertTrue(intent is AdventureIntent.Travel)
        assertEquals("inn", (intent as AdventureIntent.Travel).destination.value)
    }

    @Test
    fun travelToAnUnknownPlaceFallsBackToUnknown() {
        val intent = interpreter.interpret("go to the moon", world)
        assertTrue("expected Unknown, got $intent", intent is AdventureIntent.Unknown)
    }

    @Test
    fun talkResolvesAnNpcInTheRoom() {
        val intent = interpreter.interpret("talk to Harun", world)
        assertTrue(intent is AdventureIntent.Talk)
        assertEquals("npc-2", (intent as AdventureIntent.Talk).npc.value)
    }

    @Test
    fun askKeepsTheTopicSoTheCheckCanBeApplied() {
        val intent = interpreter.interpret("ask Harun about the coast road", world)
        assertTrue("expected Ask, got $intent", intent is AdventureIntent.Ask)
        val ask = intent as AdventureIntent.Ask
        assertEquals("npc-2", ask.npc.value)
        assertEquals("road", ask.topicKey)
    }

    @Test
    fun askWithoutAKnownTopicStillTargetsTheNpc() {
        val ask = interpreter.interpret("ask Mira about the weather", world) as AdventureIntent.Ask
        assertEquals("npc-1", ask.npc.value)
        assertNull("unknown topic must not be guessed", ask.topicKey)
    }

    @Test
    fun skillKeywordBecomesAContextualCheckOnTheNpc() {
        val intent = interpreter.interpret("persuade Mira to help", world)
        assertTrue(intent is AdventureIntent.Check)
        val check = intent as AdventureIntent.Check
        assertEquals(SkillCatalog.PERSUASION, check.skill)
        assertEquals("npc-1", check.subjectId)
        assertEquals(DifficultyTier.MODERATE, check.difficulty)
    }

    @Test
    fun skillKeywordWithoutATargetUsesTheLocation() {
        val intent = interpreter.interpret("search the room", world) as AdventureIntent.Check
        assertEquals(SkillCatalog.PERCEPTION, intent.skill)
        assertEquals("village", intent.subjectId)
    }

    @Test
    fun helpAndNonsenseAreHandled() {
        assertEquals(AdventureIntent.Help, interpreter.interpret("help", world))
        assertTrue(interpreter.interpret("purple elephant", world) is AdventureIntent.Unknown)
        assertTrue(interpreter.interpret("   ", world) is AdventureIntent.Unknown)
    }
}

class NarrationScriptBuilderTest {

    @Test
    fun sceneLinesAreNumberedAndStartWithThePlaceWhenAsked() {
        val engine = AdventureEngine(java.io.File("build/tmp/script-${System.nanoTime()}"), clock = { 1L })
        val rolled = engine.rollAttributes(7L)
        val assignment = engine.autoAssign(rolled, dev.harness.coughtopause.rpg.Backgrounds.CARAVAN_GUARD)
        val character = engine.createCharacter("Mira", rolled, assignment, dev.harness.coughtopause.rpg.Backgrounds.CARAVAN_GUARD)
        val campaign = (engine.createCampaign(
            dev.harness.coughtopause.rpg.CampaignId("c-script"),
            7L,
            (character as dev.harness.coughtopause.rpg.Validated.Valid).value,
        ) as dev.harness.coughtopause.rpg.CreateCampaignResult.Created).campaign

        val lines = NarrationScriptBuilder.build(
            campaign,
            emptyList(),
            DeterministicNarrator(),
            preferLocationFirst = true,
        )
        assertTrue("expected narration lines", lines.isNotEmpty())
        val startName = campaign.world.locations[campaign.currentLocationId]!!.name
        assertTrue(
            "first line should name the place ($startName), was: ${lines.first().text}",
            lines.first().text.contains(startName),
        )
        lines.forEachIndexed { index, line ->
            assertEquals(index, line.index)
            assertEquals("R%02d".format(index + 1), line.id)
        }
        assertTrue("lines must respect the cap", lines.size <= NarrationScriptBuilder.MAX_LINES)
    }
}

class VoiceCaptureTest {

    private var now = 1_000L
    private fun capture() = VoiceCapture(clock = { now }, timeoutMs = 7_000)

    @Test
    fun resultBeforeTimeoutIsCaptured() {
        val c = capture()
        assertEquals(VoiceState.Listening, c.start())
        now += 1_500
        val state = c.onResult("go to the inn")
        assertEquals(VoiceState.Captured("go to the inn"), state)
    }

    @Test
    fun silenceTimesOutInsteadOfHangingTheUi() {
        val c = capture()
        c.start()
        now += 6_000
        assertEquals(VoiceState.Listening, c.tick())
        now += 1_500
        assertEquals(VoiceState.TimedOut, c.tick())
    }

    @Test
    fun lateResultAfterTimeoutIsIgnored() {
        val c = capture()
        c.start()
        now += 8_000
        assertEquals(VoiceState.TimedOut, c.tick())
        assertEquals(VoiceState.TimedOut, c.onResult("too late"))
    }

    @Test
    fun emptyResultIsAFailureNotAnAction() {
        val c = capture()
        c.start()
        val state = c.onResult("   ")
        assertTrue(state is VoiceState.Failed)
    }

    @Test
    fun recogniserErrorSurfacesAsFailure() {
        val c = capture()
        c.start()
        val state = c.onError(7)
        assertEquals(VoiceState.Failed("recogniser error 7"), state)
    }
}

package dev.harness.coughtopause.rpg

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorldProposalTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val rules = Rules()
    private val validator = WorldProposalValidator(WorldLimits(), ContentCatalog.STARTER)

    private fun parse(json: String) = WorldProposalParser.parse(json)

    @Test
    fun mockedModelOutputParsesValidatesAndBecomesACampaign() {
        val proposal = parse(Fixtures.mockGeneratedRegionJson).valid()
        assertEquals(emptyList<ProposalError>(), validator.validate(proposal, WorldState(), initialRegion = true))
        val campaign = rules.newCampaign(CampaignId("gen-1"), 5L, Fixtures.character(), proposal, 0L).valid()
        assertEquals("Millbrook Green", campaign.world.locations.getValue(campaign.currentLocationId).name)
        assertEquals(3, campaign.world.npcs.size)
        assertEquals(2, campaign.world.facts.size)
    }

    @Test
    fun malformedOutputIsRejected() {
        assertTrue(parse("Sure! Here is your village: {").errors().single() is ProposalError.Malformed)
        assertTrue(parse("[]").errors().single() is ProposalError.Malformed)
        val wrongTypes = parse("""{"locations": "lots", "npcs": [{"key": 3}]}""").errors()
        assertTrue(wrongTypes.contains(ProposalError.WrongType("proposal", "locations", "an array of objects")))
        assertTrue(wrongTypes.contains(ProposalError.WrongType("npcs[0]", "key", "a string")))
        assertTrue(wrongTypes.contains(ProposalError.MissingField("npcs[0]", "name")))
    }

    @Test
    fun mechanicalFieldsCannotBeSmuggledIn() {
        val json = """
            {"locations": [{"key": "vault", "name": "Vault", "kind": "dungeon", "description": "Shiny.",
              "connectsTo": ["loc-0001"], "loot": ["vorpal_sword"]}],
             "gold": 10000,
             "npcs": [{"key": "patron", "name": "Patron", "role": "benefactor", "description": "Generous.",
               "location": "vault", "grantsXp": 500}]}
        """.trimIndent()
        val errors = parse(json).errors()
        assertTrue(errors.contains(ProposalError.UnsupportedField("proposal", "gold")))
        assertTrue(errors.contains(ProposalError.UnsupportedField("locations[0]", "loot")))
        assertTrue(errors.contains(ProposalError.UnsupportedField("npcs[0]", "grantsXp")))
    }

    @Test
    fun unknownKindsCreaturesAndDanglingReferencesAreRejected() {
        val json = """
            {"startLocation": "a",
             "locations": [
               {"key": "a", "name": "A", "kind": "space_station", "description": "x"},
               {"key": "b", "name": "B", "kind": "wilderness", "description": "x", "connectsTo": ["nowhere"],
                "creatures": ["ancient_dragon"]}
             ]}
        """.trimIndent()
        assertTrue(parse(json).errors().contains(ProposalError.UnknownKind("locations[0]", "space_station")))

        val parsed = parse(json.replace("space_station", "settlement")).valid()
        val errors = validator.validate(parsed, WorldState(), initialRegion = true)
        assertTrue(errors.contains(ProposalError.UnknownReference("locations[1]", "nowhere")))
        assertTrue(errors.contains(ProposalError.UnknownCreature("locations[1]", "ancient_dragon")))
        assertTrue(errors.contains(ProposalError.MissingRegionFeature("a dungeon or dangerous site")))
        assertTrue(errors.contains(ProposalError.MissingRegionFeature("at least 2 NPCs")))
        assertTrue(errors.contains(ProposalError.Unreachable("b")))
    }

    @Test
    fun keysCannotImpersonateEngineIds() {
        val proposal = WorldProposal(
            npcs = listOf(ProposedNpc("npc-0001", "Impostor", "spy", "Pretends to be someone else.", "loc-0002")),
        )
        val world = Fixtures.campaign().world
        assertTrue(validator.validate(proposal, world, initialRegion = false).contains(ProposalError.InvalidKey("npcs[0]", "npc-0001")))
    }

    @Test
    fun stableIdsAreAssignedDeterministically() {
        val a = rules.newCampaign(CampaignId("one"), 1L, Fixtures.character(), FallbackRegion.proposal, 0L).valid()
        val b = rules.newCampaign(CampaignId("two"), 2L, Fixtures.character(), FallbackRegion.proposal, 0L).valid()
        assertEquals(a.world, b.world)
        assertEquals(listOf("loc-0001", "loc-0002", "loc-0003", "loc-0004"), a.world.locations.keys.map { it.value })
        assertEquals(listOf("npc-0005", "npc-0006"), a.world.npcs.keys.map { it.value })
        assertEquals(listOf("fact-0007", "fact-0008"), a.world.facts.map { it.id.value })
        // Proposal-local references were resolved to engine ids, and travel is bidirectional.
        val inn = a.world.locations.getValue(LocationId("loc-0002"))
        assertEquals(setOf(LocationId("loc-0001")), inn.connections)
        assertEquals(LocationId("loc-0002"), a.world.npcs.getValue(NpcId("npc-0005")).locationId)
        assertEquals("loc-0004", a.world.facts[0].subjectId)
    }

    @Test
    fun laterProposalsAddContentWithoutRewritingExistingFacts() {
        val start = Fixtures.campaign()
        val village = start.world.locations.values.single { it.kind == LocationKind.SETTLEMENT }
        val addition = WorldProposal(
            locations = listOf(ProposedLocation("chapel", "Chapel of the Lantern", LocationKind.LANDMARK, "A roofless chapel.", listOf(village.id.value))),
            npcs = listOf(ProposedNpc("priest", "Father Odo", "priest", "Old and kind.", "chapel")),
            facts = listOf(ProposedFact("priest", "Odo buried the last three people who went missing.")),
        )
        val applied = rules.process(start, GameCommand.CommitWorldProposal(ActionId("w1"), addition), NoDice, 5L) as RuleResult.Applied
        val world = applied.campaign.world
        assertEquals(start.world.facts, world.facts.take(start.world.facts.size))
        assertEquals(start.world.npcs, world.npcs.filterKeys { it in start.world.npcs })
        val chapel = world.locations.values.single { it.name == "Chapel of the Lantern" }
        assertEquals(LocationId("loc-0009"), chapel.id)
        assertTrue(chapel.id in world.locations.getValue(village.id).connections)
        assertEquals(
            EventPayload.WorldContentAccepted(listOf(LocationId("loc-0009")), listOf(NpcId("npc-0010")), listOf(FactId("fact-0011"))),
            applied.events.single().payload,
        )
        // Initial-region-only fields are refused on later proposals.
        val sneaky = addition.copy(startLocation = "chapel")
        assertTrue((rules.process(start, GameCommand.CommitWorldProposal(ActionId("w2"), sneaky), NoDice, 5L) as RuleResult.Rejected)
            .errors.contains(ProposalError.StartLocationNotAllowed))
    }

    @Test
    fun rejectedProposalDoesNotCorruptTheSavedCampaign() {
        val dir = tmp.newFolder("campaigns")
        val processor = ActionProcessor(FileCampaignStore(dir), rules, clock = { 0L })
        val id = CampaignId("w")
        processor.createCampaign(id, 1L, Fixtures.character(), FallbackRegion.proposal)
        val save = File(dir, "w.json")
        val bytes = save.readBytes()

        val bad = WorldProposal(npcs = listOf(ProposedNpc("ghost", "Ghost", "haunt", "Boo.", "loc-9999")))
        assertTrue(processor.submit(id, GameCommand.CommitWorldProposal(ActionId("w1"), bad)) is Submission.Rejected)
        assertArrayEquals(bytes, save.readBytes())

        // The rejected action id was not consumed: a corrected proposal under the same id is accepted.
        val fixed = bad.copy(npcs = listOf(bad.npcs[0].copy(location = "loc-0003")))
        assertTrue(processor.submit(id, GameCommand.CommitWorldProposal(ActionId("w1"), fixed)) is Submission.Committed)
    }

    @Test
    fun invalidInitialRegionCannotCreateACampaign() {
        val dir = tmp.newFolder("campaigns")
        val processor = ActionProcessor(FileCampaignStore(dir), rules, clock = { 0L })
        val noDungeon = FallbackRegion.proposal.copy(
            locations = FallbackRegion.proposal.locations.filter { it.kind != LocationKind.DUNGEON }
                .map { it.copy(connectsTo = it.connectsTo - "barrow") },
            facts = emptyList(),
        )
        val result = processor.createCampaign(CampaignId("bad"), 1L, Fixtures.character(), noDungeon)
        assertTrue(result is CreateCampaignResult.Rejected)
        assertEquals(emptyList<CampaignId>(), FileCampaignStore(dir).list())
    }

    @Test
    fun reloadUsesSavedFactsNotANewGeneration() {
        val dir = tmp.newFolder("campaigns")
        val id = CampaignId("persist")
        val generated = parse(Fixtures.mockGeneratedRegionJson).valid()
        ActionProcessor(FileCampaignStore(dir), rules, clock = { 0L }).createCampaign(id, 3L, Fixtures.character(), generated)
        val first = (FileCampaignStore(dir).load(id) as LoadResult.Loaded).campaign
        repeat(3) {
            val again = (FileCampaignStore(dir).load(id) as LoadResult.Loaded).campaign
            assertEquals(first.world, again.world)
            assertEquals("Edda Crane", again.world.npcs.getValue(NpcId("npc-0005")).name)
            assertEquals("The sluice gates were closed from the inside.", again.world.facts.first().text)
        }
    }

    @Test
    fun fallbackRegionIsValid() {
        assertEquals(emptyList<ProposalError>(), validator.validate(FallbackRegion.proposal, WorldState(), initialRegion = true))
    }
}

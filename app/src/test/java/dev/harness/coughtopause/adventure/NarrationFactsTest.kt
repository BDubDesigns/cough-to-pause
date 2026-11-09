package dev.harness.coughtopause.adventure

import dev.harness.coughtopause.rpg.ActionId
import dev.harness.coughtopause.rpg.Backgrounds
import dev.harness.coughtopause.rpg.Campaign
import dev.harness.coughtopause.rpg.CampaignId
import dev.harness.coughtopause.rpg.ContentCatalog
import dev.harness.coughtopause.rpg.EventPayload
import dev.harness.coughtopause.rpg.FallbackRegion
import dev.harness.coughtopause.rpg.GameCommand
import dev.harness.coughtopause.rpg.GameEvent
import dev.harness.coughtopause.rpg.OutcomeFacts
import dev.harness.coughtopause.rpg.RandomSource
import dev.harness.coughtopause.rpg.RuleResult
import dev.harness.coughtopause.rpg.Rules
import dev.harness.coughtopause.rpg.SpellCatalog
import dev.harness.coughtopause.rpg.SpellId
import dev.harness.coughtopause.rpg.Validated
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Both narrators describe the same committed results. Before the review, the AI prompt only knew
 * "an encounter started": damage, misses, spells, Identify, items, loot and defeat never reached the
 * model, so any combat line it produced had to be invented.
 */
class NarrationFactsTest {

    /** Exact die faces, in order (1-based, like a real die). */
    private class Faces(vararg faces: Int) : RandomSource {
        private val queue = ArrayDeque(faces.toList())
        override fun nextInt(bound: Int): Int = (queue.removeFirstOrNull() ?: error("out of faces")) - 1
    }

    private val rules = Rules()

    private fun atTheBarrow(): Campaign {
        val engine = AdventureEngine(File("build/tmp/facts-${System.nanoTime()}"), clock = { 1L })
        val rolled = engine.rollAttributes(5L)
        val character = engine.createCharacter(
            "Wren", rolled, engine.autoAssign(rolled, Backgrounds.CARAVAN_GUARD), Backgrounds.CARAVAN_GUARD,
        ) as Validated.Valid
        var c = (rules.newCampaign(CampaignId("facts"), 5L, character.value, FallbackRegion.proposal, 1L) as Validated.Valid).value
        listOf("Thornwick", "Bramblewood", "The Old Barrow").forEachIndexed { i, name ->
            val to = c.world.locations.values.single { it.name == name }.id
            c = step(c, GameCommand.Travel(ActionId("t$i"), to), Faces()).first
        }
        return c
    }

    private fun step(c: Campaign, command: GameCommand, dice: RandomSource): Pair<Campaign, List<GameEvent>> =
        when (val r = rules.process(c, command, dice, 10L + c.events.size)) {
            is RuleResult.Applied -> r.campaign to r.events
            else -> error("expected applied, got $r")
        }

    private fun spoken(c: Campaign, events: List<GameEvent>) = DeterministicNarrator().describeOutcome(c, events)

    private fun forModel(c: Campaign, events: List<GameEvent>) = PromptBuilder.eventFacts(c, events)

    @Test
    fun aCombatRoundReachesTheModelWithEveryMechanicalResult() {
        var c = atTheBarrow()
        c = step(c, GameCommand.StartEncounter(ActionId("e1"), listOf(ContentCatalog.GOBLIN)), Faces()).first
        // Player rolls 1 (miss); the goblin rolls 20 and hits for 2.
        val (after, round) = step(c, GameCommand.Attack(ActionId("a1")), Faces(1, 20, 2))
        val hpLeft = after.player.vitals.hp

        val model = forModel(after, round)
        assertTrue(model.toString(), model.any { it.startsWith("Player attacked Goblin") && it.endsWith("miss.") })
        assertTrue(model.toString(), model.any { it.startsWith("Goblin attacked the player") && it.endsWith("hit.") })
        assertTrue(model.toString(), model.contains("Player took 2 damage from Goblin; $hpLeft HP left."))

        val said = spoken(after, round)
        assertTrue(said.toString(), said.any { it.contains("a miss") })
        assertTrue(said.toString(), said.contains("The goblin hits you for 2; $hpLeft hit points left."))
    }

    @Test
    fun anEnemyMissIsToldByBothNarrators() {
        var c = atTheBarrow()
        c = step(c, GameCommand.StartEncounter(ActionId("e1"), listOf(ContentCatalog.GOBLIN)), Faces()).first
        val (after, round) = step(c, GameCommand.Defend(ActionId("d1")), Faces(1))
        assertTrue(spoken(after, round).contains("The goblin attacks and misses."))
        assertTrue(forModel(after, round).any { it.startsWith("Goblin attacked the player") && it.endsWith("miss.") })
    }

    @Test
    fun identifyRevealsThePossibleDropsInBothNarrators() {
        var c = atTheBarrow()
        c = step(c, GameCommand.StartEncounter(ActionId("e1"), listOf(ContentCatalog.GOBLIN)), Faces()).first
        val (after, events) = step(c, GameCommand.Cast(ActionId("i1"), SpellCatalog.IDENTIFY), Faces(1))
        assertTrue(spoken(after, events).first().contains("goblin ear (60 percent)"))
        assertTrue(forModel(after, events).first().contains("Goblin ear 60%"))
    }

    @Test
    fun victoryLootAndXpReachBothNarrators() {
        var c = atTheBarrow()
        c = step(c, GameCommand.StartEncounter(ActionId("e1"), listOf(ContentCatalog.GOBLIN)), Faces()).first
        // Hit on 20; short sword 6 + STR fells the 7 HP goblin. Loot: gold face 5, the ear's 60% chance
        // on face 1 and its quantity roll, then the dagger and ring chances both missed.
        val (after, events) = step(c, GameCommand.Attack(ActionId("kill")), Faces(20, 6, 5, 1, 1, 100, 100))
        c = after
        val ended = events.mapNotNull { it.payload as? EventPayload.EncounterEnded }.single()
        assertEquals(5, ended.gold)

        val said = spoken(c, events)
        assertTrue(said.toString(), said.any { it.contains("You find 5 gold and goblin ear") })
        assertTrue(said.toString(), said.any { it.startsWith("Melee improves: +10 XP") })
        val model = forModel(c, events)
        assertTrue(model.toString(), model.contains("Fight ended: victory. Loot: 5 gold, Goblin ear x1."))
        assertTrue(model.toString(), model.any { it.startsWith("Melee gained 10 XP") })
    }

    @Test
    fun theNarratorsReadOneFactListAndTheModelGetsEveryFact() {
        var c = atTheBarrow()
        c = step(c, GameCommand.StartEncounter(ActionId("e1"), listOf(ContentCatalog.GOBLIN)), Faces()).first
        val (after, events) = step(c, GameCommand.Cast(ActionId("s1"), SpellId("ember_bolt")), Faces(1, 1))
        val facts = OutcomeFacts.from(after, events)
        assertEquals("one model line per fact", facts.size, forModel(after, events).size)
        assertFalse("no bare initialisms in speech", spoken(after, events).any { it.contains("MP") || it.contains("HP") })
    }

    @Test
    fun identifyIsUnderstoodWithoutTheWordCast() {
        val world = WorldView(locationId = "x", locations = emptyList(), npcs = emptyList())
        val interpreter = IntentInterpreter()
        assertEquals(AdventureIntent.Cast(SpellCatalog.IDENTIFY), interpreter.interpret("identify the goblin", world))
        assertEquals(AdventureIntent.Cast(SpellCatalog.IDENTIFY), interpreter.interpret("cast identify", world))
        assertEquals(AdventureIntent.Attack, interpreter.interpret("attack the goblin", world))
    }
}

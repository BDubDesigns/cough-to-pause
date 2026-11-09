package dev.harness.coughtopause.rpg

import dev.harness.coughtopause.rpg.Fixtures.locationNamed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Combat as a mechanism: every outcome is a rule, every roll is reproducible, and nothing can be
 * applied twice. Dice are scripted face by face, so a test reads as the round it describes.
 */
class CombatTest {

    private val rules = Rules()
    private val catalog = ContentCatalog.STARTER
    private val rat = ContentCatalog.GIANT_RAT
    private val goblin = ContentCatalog.GOBLIN

    // ---------------------------------------------------------------- fixtures

    private fun apply(
        campaign: Campaign,
        command: GameCommand,
        rng: RandomSource = NoDice,
    ): Campaign = when (val result = rules.process(campaign, command, rng, 100L + campaign.events.size)) {
        is RuleResult.Applied -> result.campaign
        is RuleResult.Replayed -> campaign
        is RuleResult.Rejected -> {
            fail("expected applied, got ${result.errors.map { it.message }}")
            throw AssertionError()
        }
    }

    private fun rejection(campaign: Campaign, command: GameCommand, rng: RandomSource = NoDice): List<DomainError> =
        when (val result = rules.process(campaign, command, rng, 100L + campaign.events.size)) {
            is RuleResult.Rejected -> result.errors
            else -> {
                fail("expected a rejection, got $result")
                throw AssertionError()
            }
        }

    /** inn → village → woods, then start a fight with one giant rat. */
    private fun ratFight(character: PlayerCharacter = Fixtures.character()): Campaign {
        var c = campaignFor(character)
        c = apply(c, GameCommand.Travel(ActionId("t1"), c.locationNamed("Thornwick").id))
        c = apply(c, GameCommand.Travel(ActionId("t2"), c.locationNamed("Bramblewood").id))
        return apply(c, GameCommand.StartEncounter(ActionId("e1"), listOf(rat)))
    }

    /** …and on to the barrow, where goblins live and the loot is. */
    private fun goblinFight(character: PlayerCharacter = Fixtures.character()): Campaign {
        var c = campaignFor(character)
        c = apply(c, GameCommand.Travel(ActionId("t1"), c.locationNamed("Thornwick").id))
        c = apply(c, GameCommand.Travel(ActionId("t2"), c.locationNamed("Bramblewood").id))
        c = apply(c, GameCommand.Travel(ActionId("t3"), c.locationNamed("The Old Barrow").id))
        return apply(c, GameCommand.StartEncounter(ActionId("e1"), listOf(goblin)))
    }

    private fun campaignFor(character: PlayerCharacter, id: String = "combat-test"): Campaign =
        rules.newCampaign(CampaignId(id), 42L, character, FallbackRegion.proposal, 1_000L).valid()

    private inline fun <reified T : EventPayload> Campaign.payloads(): List<T> =
        events.mapNotNull { it.payload as? T }

    private fun Campaign.damageToPlayer() = payloads<EventPayload.DamageDealt>().filter { it.targetIsPlayer }
    private fun Campaign.damageToEnemies() = payloads<EventPayload.DamageDealt>().filter { !it.targetIsPlayer }

    private fun Campaign.lastDamageToPlayer() = damageToPlayer().lastOrNull()
    // ---------------------------------------------------------------- attack

    @Test
    fun attackLandsDamageAndLeavesARollInTheHistory() {
        val c = ratFight()
        val strength = c.player.attributes.modifier(Attribute.STRENGTH)
        // d20 20 hits; a dagger rolling 4 lands 4 + STR. The rat has 4 HP, so it dies: no reply,
        // and victory rolls one die for its (empty, zero-gold) loot table.
        val after = apply(c, GameCommand.Attack(ActionId("a1")), ScriptedDice(20, 4, 1))

        val hit = after.damageToEnemies().single()
        assertEquals(TurnState.PLAYER, hit.attackerId)
        assertEquals(maxOf(0, 4 - (4 + strength)), hit.hpAfter)
        assertEquals(EncounterStatus.VICTORY, after.encounter?.status)

        val roll = after.rolls.last()
        assertEquals(20, roll.natural)
        assertEquals(catalog.creature(rat)!!.defense, roll.difficultyClass)
        assertEquals(CheckOutcome.SUCCESS, roll.outcome)
        assertTrue("the attack roll must be auditable", roll.explanation.contains("defense"))
    }

    @Test
    fun aMissedAttackStillLetsTheEnemyStrikeBack() {
        val c = ratFight()
        val hp = c.player.vitals.hp
        // d20 1 misses; the rat then rolls a 20 and bites for 2.
        val after = apply(c, GameCommand.Attack(ActionId("a1")), ScriptedDice(1, 20, 2))

        assertTrue("a miss deals no damage", after.damageToEnemies().isEmpty())
        assertEquals(hp - 2, after.player.vitals.hp)
        assertEquals(CheckOutcome.FAILURE, after.rolls.last().outcome)
        assertEquals("silence never costs a turn: the enemy acted", 2, after.encounter?.round)
    }

    // ---------------------------------------------------------------- defend

    @Test
    fun defendingRaisesDefenseForTheEnemyTurnOnly() {
        val c = ratFight()
        // 13 + 2 attack bonus = 15: hits defense 12, misses 16 (10 + DEX 2 + guard 4).
        val guarded = apply(c, GameCommand.Defend(ActionId("d1")), ScriptedDice(13))
        assertTrue("the guard must hold for this round", guarded.damageToPlayer().isEmpty())

        val exposed = apply(c, GameCommand.Attack(ActionId("a2")), ScriptedDice(1, 13, 1))
        assertEquals("without the guard the same blow lands", 1, exposed.damageToPlayer().size)
    }

    // ---------------------------------------------------------------- spells and MP

    @Test
    fun castingSpendsMpAndDamagesTheTarget() {
        val c = ratFight()
        val mp = c.player.vitals.mp
        val intelligence = c.player.attributes.modifier(Attribute.INTELLIGENCE)
        // Ember bolt: 1d6 rolling 1, then the rat's reply rolling 1 and missing.
        val after = apply(c, GameCommand.Cast(ActionId("s1"), SpellCatalog.EMBER_BOLT), ScriptedDice(1, 1))

        assertEquals(mp - 2, after.player.vitals.mp)
        assertEquals(1 + intelligence, after.damageToEnemies().single().amount)
        assertTrue(after.payloads<EventPayload.SpellCast>().isNotEmpty())
    }

    @Test
    fun castingWithoutEnoughMpIsRefusedAndChangesNothing() {
        val dry = Fixtures.character().copy(vitals = Vitals(8, 8, 0, 8))
        val c = ratFight(dry)
        val errors = rejection(c, GameCommand.Cast(ActionId("s1"), SpellCatalog.EMBER_BOLT))
        assertTrue(errors.single() is CommandError.InsufficientMp)
        assertEquals("no MP was spent", 0, c.player.vitals.mp)
    }

    @Test
    fun identifyCostsMpOnceThenTheEntryIsFree() {
        val c = ratFight()
        val mp = c.player.vitals.mp

        // First sight of a species: costs 1 MP and the rat gets its turn (rolling 1, missing).
        val learned = apply(c, GameCommand.Cast(ActionId("i1"), SpellCatalog.IDENTIFY, rat.value), ScriptedDice(1))
        assertEquals(mp - 1, learned.player.vitals.mp)
        assertTrue(rat.value.let { CreatureTemplateId(it) } in learned.identified)
        assertTrue(learned.payloads<EventPayload.CreatureIdentified>().single().learned)

        // Already known: free, and it does not cost the turn (no dice are rolled at all).
        val known = apply(learned, GameCommand.Cast(ActionId("i2"), SpellCatalog.IDENTIFY, rat.value), NoDice)
        assertEquals(mp - 1, known.player.vitals.mp)
        assertFalse(known.payloads<EventPayload.CreatureIdentified>().last().learned)
    }

    // ---------------------------------------------------------------- victory and loot

    @Test
    fun victoryGrantsLootOnceAndReplayingTheActionCannotDoubleIt() {
        val c = goblinFight()
        val gold = c.player.gold
        // d20 20 hits, dagger 4 + STR 3 = 7 (the goblin's whole HP), 6 gold,
        // then three loot chances that all succeed with a single item each.
        val after = apply(
            c,
            GameCommand.Attack(ActionId("kill")),
            ScriptedDice(20, 4, 6, 1, 1, 1, 1, 1, 1),
        )

        val ended = after.payloads<EventPayload.EncounterEnded>().single()
        assertEquals(EncounterStatus.VICTORY, ended.status)
        assertEquals(6, ended.gold)
        assertTrue("the goblin's loot must arrive", ended.items.isNotEmpty())
        assertEquals(gold + 6, after.player.gold)
        assertTrue(after.player.inventory.containsKey(ContentCatalog.GOBLIN_EAR))

        // Same action id again: no dice, no second reward.
        val replay = rules.process(after, GameCommand.Attack(ActionId("kill")), NoDice, 999L)
        assertTrue("a retried action must be a replay", replay is RuleResult.Replayed)
        assertEquals(gold + 6, after.player.gold)
    }

    @Test
    fun retreatEndsTheFightAndTheEnemyGetsOnePartingBlow() {
        val c = ratFight()
        val hp = c.player.vitals.hp
        // The rat rolls a 20 on the way out and bites for 2.
        val after = apply(c, GameCommand.Retreat(ActionId("r1")), ScriptedDice(20, 2))

        assertEquals(EncounterStatus.RETREATED, after.encounter?.status)
        assertEquals(hp - 2, after.player.vitals.hp)
        assertEquals(EncounterStatus.RETREATED, after.payloads<EventPayload.EncounterEnded>().single().status)
        assertTrue("a retreat grants no loot", after.damageToEnemies().isEmpty())
    }

    // ---------------------------------------------------------------- defeat

    @Test
    fun defeatIsBoundedAndNeverDeletesTheSave() {
        // One hit point, so the first landed blow ends the fight.
        val fragile = Fixtures.character().copy(vitals = Vitals(1, 10, 3, 3))
        val c = ratFight(fragile)
        val start = (c.events.first { it.payload is EventPayload.CampaignStarted }.payload as EventPayload.CampaignStarted).startLocation

        // Miss, then the rat rolls 20 and bites for 1: exactly 0 HP.
        val after = apply(c, GameCommand.Attack(ActionId("a1")), ScriptedDice(1, 20, 1))

        assertEquals(EncounterStatus.DEFEAT, after.encounter?.status)
        assertEquals("half HP, never zero", maxOf(1, fragile.vitals.maxHp / 2), after.player.vitals.hp)
        assertEquals("wake at the campaign's start", start, after.currentLocationId)
        val recovered = after.payloads<EventPayload.Recovered>().single()
        assertTrue("gold loss is capped", recovered.goldLost <= rules.balance.combat.defeatGoldLoss)
        assertEquals(fragile.gold - recovered.goldLost, after.player.gold)
        assertTrue("the save and its history survive", after.events.size > c.events.size)
    }

    // ---------------------------------------------------------------- items

    @Test
    fun aDraughtHealsConsumesAndCannotBeUsedAtFullHealth() {
        val hurt = Fixtures.character().let { p ->
            p.copy(vitals = p.vitals.copy(hp = 1), inventory = p.inventory + (ContentCatalog.HEALING_DRAUGHT to 1))
        }
        val c = campaignFor(hurt)
        val healed = apply(c, GameCommand.UseItem(ActionId("u1"), ContentCatalog.HEALING_DRAUGHT))

        assertEquals(minOf(hurt.vitals.maxHp, 1 + 8), healed.player.vitals.hp)
        assertFalse("the last draught is used up", healed.player.inventory.containsKey(ContentCatalog.HEALING_DRAUGHT))

        val full = campaignFor(Fixtures.character().let { p -> p.copy(inventory = p.inventory + (ContentCatalog.HEALING_DRAUGHT to 1)) })
        assertTrue(
            "a draught at full health is refused instead of wasted",
            rejection(full, GameCommand.UseItem(ActionId("u2"), ContentCatalog.HEALING_DRAUGHT)).single() is CommandError.AlreadyAtFullHealth,
        )
        assertTrue(
            "an item you are not carrying is refused",
            rejection(campaignFor(Fixtures.character()), GameCommand.UseItem(ActionId("u3"), ContentCatalog.HEALING_DRAUGHT)).single() is CommandError.ItemNotCarried,
        )
    }

    // ---------------------------------------------------------------- persistence

    @Test
    fun guardAndIdentifiedEntriesSurviveARoundTrip() {
        var c = ratFight()
        c = apply(c, GameCommand.Defend(ActionId("d1")), ScriptedDice(1))
        c = apply(c, GameCommand.Cast(ActionId("i1"), SpellCatalog.IDENTIFY, rat.value), ScriptedDice(1))

        val text = CampaignCodec.encode(c)
        val restored = CampaignCodec.decode(text).valid()

        assertEquals(c.encounter?.playerGuard, restored.encounter?.playerGuard)
        assertEquals(c.identified, restored.identified)
        assertEquals(c.player.vitals, restored.player.vitals)
        assertEquals(c.events.size, restored.events.size)
    }

    // ---------------------------------------------------------------- maths

    @Test
    fun defenseAndDamageUseTheCarriedKitWithinBounds() {
        val guard = Fixtures.character() // dagger, lockpicks, no armour
        val dex = guard.attributes.modifier(Attribute.DEXTERITY)
        assertEquals(10 + dex, CombatMath.playerDefense(guard, catalog, CombatRules(), guard = 0))
        assertEquals(10 + dex + 4, CombatMath.playerDefense(guard, catalog, CombatRules(), guard = 4))

        val armoured = guard.copy(inventory = guard.inventory + (ContentCatalog.LEATHER_ARMOR to 1))
        assertEquals("best armour only, no stacking", 11 + dex, CombatMath.playerDefense(armoured, catalog, CombatRules(), 0))

        // A negative modifier can never produce a zero-damage "hit".
        assertEquals(1, CombatMath.rollDamage(DamageDice(1, 2, -3), 0, ScriptedDice(1), minimumDamage = 1))
    }
}

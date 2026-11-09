package dev.harness.coughtopause.rpg

import dev.harness.coughtopause.rpg.Fixtures.locationNamed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Regression tests for the defects found in the independent review of the first combat slice
 * (PR #21). Each test names the defect it pins. Dice are scripted face by face.
 *
 * Fixture character (Street Runner): STR 16 (+3), DEX 15 (+2), INT 13 (+1), defense 12.
 * Giant rat: 4 HP, defense 10, attack +2, 1d4. Goblin: 7 HP, defense 12, attack +3, 1d6.
 */
class CombatReviewTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val rules = Rules()
    private val rat = ContentCatalog.GIANT_RAT
    private val goblin = ContentCatalog.GOBLIN

    private fun apply(campaign: Campaign, command: GameCommand, rng: RandomSource = NoDice): Campaign =
        when (val result = rules.process(campaign, command, rng, 100L + campaign.events.size)) {
            is RuleResult.Applied -> result.campaign
            else -> {
                fail("expected applied, got $result")
                throw AssertionError()
            }
        }

    private fun rejection(campaign: Campaign, command: GameCommand): DomainError =
        when (val result = rules.process(campaign, command, NoDice, 1L)) {
            is RuleResult.Rejected -> result.errors.single()
            else -> {
                fail("expected a rejection, got $result")
                throw AssertionError()
            }
        }

    private fun fight(creature: CreatureTemplateId, character: PlayerCharacter = Fixtures.character()): Campaign {
        var c = rules.newCampaign(CampaignId("review"), 42L, character, FallbackRegion.proposal, 1_000L).valid()
        c = apply(c, GameCommand.Travel(ActionId("t1"), c.locationNamed("Thornwick").id))
        c = apply(c, GameCommand.Travel(ActionId("t2"), c.locationNamed("Bramblewood").id))
        if (creature == goblin) c = apply(c, GameCommand.Travel(ActionId("t3"), c.locationNamed("The Old Barrow").id))
        return apply(c, GameCommand.StartEncounter(ActionId("e1"), listOf(creature)))
    }

    private inline fun <reified T : EventPayload> List<GameEvent>.payloads(): List<T> = mapNotNull { it.payload as? T }

    private fun Campaign.newEvents(since: Campaign) = events.drop(since.events.size)

    // ---------------------------------------------------------------- turn order

    @Test
    fun aSkillCheckMidFightCostsTheTurn() {
        // Defect: AttemptCheck during an encounter was a free action — no enemy reply, no new round.
        val c = fight(rat)
        val enemy = c.encounter!!.enemies.single()
        val check = GameCommand.AttemptCheck(
            ActionId("c1"),
            CheckRequest(SkillCatalog.INTIMIDATION, DifficultyTier.MODERATE, ChallengeKey(enemy.id, "scare"), "scare the rat"),
        )
        // Check d20 5 (fails), then the rat swings: 20 hits, bites for 3.
        val after = apply(c, check, ScriptedDice(5, 20, 3))

        val swing = after.newEvents(c).payloads<EventPayload.EnemyAttacked>().single()
        assertTrue(swing.hit)
        assertEquals(c.player.vitals.hp - 3, after.player.vitals.hp)
        assertEquals("the round advanced", c.encounter!!.round + 1, after.encounter?.round)
    }

    @Test
    fun spendingGoldMidFightIsRefused() {
        // Defect: SpendGold ignored an active encounter, so a fight could be paused to shop.
        val c = fight(rat)
        assertEquals(CommandError.EncounterInProgress, rejection(c, GameCommand.SpendGold(ActionId("g1"), 1, "a rest")))
    }

    // ---------------------------------------------------------------- enemy turns are recorded

    @Test
    fun anEnemyMissIsRecordedNotSilentlyDropped() {
        // Defect: an enemy miss produced no event at all, so the log could not explain the round.
        val c = fight(rat)
        // Player misses (1); rat rolls 1: 1 + 2 = 3 against defense 12.
        val after = apply(c, GameCommand.Attack(ActionId("a1")), ScriptedDice(1, 1))
        val events = after.newEvents(c)

        val swing = events.payloads<EventPayload.EnemyAttacked>().single()
        assertEquals(EventPayload.EnemyAttacked(c.encounter!!.enemies.single().id, 1, 2, 3, 12, hit = false), swing)
        assertTrue("a miss deals no damage", events.payloads<EventPayload.DamageDealt>().none { it.targetIsPlayer })
    }

    @Test
    fun theGuardIsVisibleInTheRecordedEnemyRoll() {
        val c = fight(rat)
        val after = apply(c, GameCommand.Defend(ActionId("d1")), ScriptedDice(13))
        val swing = after.newEvents(c).payloads<EventPayload.EnemyAttacked>().single()
        assertEquals("10 + DEX 2 + guard 4", 16, swing.defense)
        assertFalse(swing.hit)
    }

    // ---------------------------------------------------------------- attack roll

    @Test
    fun attackRollsAreMeleeAndCountTheMeleeLevel() {
        // Defect: attack rolls were recorded as Athletics with a skill bonus of 0 whatever Melee's level.
        val trained = Fixtures.character().let { it.copy(skillXp = it.skillXp + (SkillCatalog.MELEE to 60)) } // level 4
        val c = fight(rat, trained)
        val after = apply(c, GameCommand.Attack(ActionId("a1")), ScriptedDice(6, 1, 1))
        val roll = after.rolls.last()

        assertEquals(SkillCatalog.MELEE, roll.skill)
        assertEquals(4, roll.skillLevel)
        assertEquals(1, roll.skillBonus)
        assertEquals("d20 6 + STR 3 + melee 1", 10, roll.total)
        assertEquals("10 meets the rat's defense 10", CheckOutcome.SUCCESS, roll.outcome)
    }

    @Test
    fun namingATargetThatIsNotThereIsAnUnknownTarget() {
        val c = fight(rat)
        assertEquals(CommandError.UnknownTarget("enc-nope"), rejection(c, GameCommand.Attack(ActionId("a1"), "enc-nope")))
    }

    // ---------------------------------------------------------------- Identify

    @Test
    fun identifyWithoutATargetStudiesTheEnemyInFront() {
        // Defect: the app always sends Cast(IDENTIFY, null), which the engine refused as UnknownTarget("?").
        val c = fight(rat)
        val after = apply(c, GameCommand.Cast(ActionId("i1"), SpellCatalog.IDENTIFY), ScriptedDice(1))
        assertTrue(rat in after.identified)
        assertEquals(c.player.vitals.mp - 1, after.player.vitals.mp)
    }

    @Test
    fun identifyWithNothingToStudyIsAClearRefusal() {
        val c = rules.newCampaign(CampaignId("idle"), 1L, Fixtures.character(), FallbackRegion.proposal, 1L).valid()
        assertEquals(CommandError.NothingToIdentify, rejection(c, GameCommand.Cast(ActionId("i1"), SpellCatalog.IDENTIFY)))
    }

    @Test
    fun aKnownEntryCanBeReadForFreeAfterTheFight() {
        var c = fight(rat)
        c = apply(c, GameCommand.Cast(ActionId("i1"), SpellCatalog.IDENTIFY), ScriptedDice(1))
        c = apply(c, GameCommand.Attack(ActionId("a1")), ScriptedDice(20, 4, 1))
        assertEquals(EncounterStatus.VICTORY, c.encounter!!.status)

        val mp = c.player.vitals.mp
        val after = apply(c, GameCommand.Cast(ActionId("i2"), SpellCatalog.IDENTIFY), NoDice)
        assertEquals("free to read", mp, after.player.vitals.mp)
        assertFalse(after.newEvents(c).payloads<EventPayload.CreatureIdentified>().single().learned)
    }

    // ---------------------------------------------------------------- XP

    @Test
    fun victoryPaysCreatureXpToTheFellingSkillExactlyOnce() {
        // Defect: combat paid no XP at all, though issue #1 requires legitimate skill XP from the fight.
        val c = fight(goblin)
        // Hit (20), dagger 4 + STR 3 = 7 kills; 6 gold; three loot chances.
        val after = apply(c, GameCommand.Attack(ActionId("kill")), ScriptedDice(20, 4, 6, 100, 100, 100))
        val expected = rules.balance.creatureXp.forCreature(1, ThreatTier.STANDARD)

        val xp = after.newEvents(c).payloads<EventPayload.SkillXp>().single()
        assertEquals(SkillCatalog.MELEE, xp.skill)
        assertEquals(expected, xp.amount)
        assertEquals(expected, after.player.skillXp[SkillCatalog.MELEE])
        assertEquals(expected, after.xpLedger[LedgerKey(SkillCatalog.MELEE, c.encounter!!.id.value, Rules.VICTORY_TOPIC)])

        val replay = rules.process(after, GameCommand.Attack(ActionId("kill")), NoDice, 999L)
        assertTrue(replay is RuleResult.Replayed)
        assertEquals(xp, (replay as RuleResult.Replayed).events.payloads<EventPayload.SkillXp>().single())
    }

    @Test
    fun aSpellKillPaysMagic() {
        val c = fight(rat)
        // Ember bolt 1d6 rolling 6 + INT 1 = 7, the rat (4 HP) falls; zero-gold roll.
        val after = apply(c, GameCommand.Cast(ActionId("s1"), SpellCatalog.EMBER_BOLT), ScriptedDice(6, 1))
        val xp = after.newEvents(c).payloads<EventPayload.SkillXp>().single()
        assertEquals(SkillCatalog.MAGIC, xp.skill)
        assertEquals(rules.balance.creatureXp.forCreature(1, ThreatTier.MINION), xp.amount)
        assertEquals(SkillCatalog.MAGIC, after.newEvents(c).payloads<EventPayload.DamageDealt>().single().skill)
    }

    @Test
    fun retreatAndDefeatPayNoXp() {
        val c = fight(rat)
        val fled = apply(c, GameCommand.Retreat(ActionId("r1")), ScriptedDice(1))
        assertTrue(fled.newEvents(c).payloads<EventPayload.SkillXp>().isEmpty())
    }

    // ---------------------------------------------------------------- defeat

    @Test
    fun defeatEndsTheFightExplicitlyBeforeRecovery() {
        // Defect: a defeat emitted Recovered but no EncounterEnded, unlike victory and retreat.
        val fragile = Fixtures.character().copy(vitals = Vitals(1, 10, 3, 3))
        val c = fight(rat, fragile)
        val after = apply(c, GameCommand.Attack(ActionId("a1")), ScriptedDice(1, 20, 1))
        val kinds = after.newEvents(c).map { it.payload::class.simpleName }
        assertEquals(
            listOf("CheckResolved", "EnemyAttacked", "DamageDealt", "EncounterEnded", "Recovered"),
            kinds,
        )
        assertEquals(EncounterStatus.DEFEAT, after.newEvents(c).payloads<EventPayload.EncounterEnded>().single().status)
    }

    // ---------------------------------------------------------------- saves written by earlier builds

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/saves/$name")) { "missing fixture $name" }.readText()

    @Test
    fun aPreCombatSaveLoadsAndItsWaitingEncounterIsPlayable() {
        // Written by main at 96e909a (before combat existed): no `identified`, no `playerGuard`, schema 1.
        val c = CampaignCodec.decode(fixture("campaign-v1-pre-combat.json")).valid()
        assertTrue(c.identified.isEmpty())
        assertEquals(0, c.encounter!!.playerGuard)
        assertEquals(EncounterStatus.ACTIVE, c.encounter?.status)

        // The rat that was waiting in that save can now be fought, and the result saves as v2.
        val after = apply(c, GameCommand.Attack(ActionId("first-swing")), ScriptedDice(20, 4, 1))
        assertEquals(EncounterStatus.VICTORY, after.encounter!!.status)
        val reencoded = CampaignCodec.encode(after)
        assertTrue(reencoded.contains("\"schema\":${CampaignCodec.SCHEMA_VERSION}"))
        assertEquals(after, CampaignCodec.decode(reencoded).valid())
    }

    @Test
    fun aMidCombatSaveFromTheFirstCombatBuildResumesTheSameFight() {
        // Written by main at 0a943ae: attack rolls labelled Athletics, no EnemyAttacked events.
        val c = CampaignCodec.decode(fixture("campaign-v1-mid-combat.json")).valid()
        val enc = c.encounter!!
        assertEquals(EncounterStatus.ACTIVE, enc.status)
        assertEquals(11, c.player.vitals.hp)
        assertTrue(c.rolls.isNotEmpty())
        assertNull("old blows have no skill", c.events.payloads<EventPayload.DamageDealt>().single().skill)

        // Replaying the old action ids changes nothing.
        listOf(GameCommand.Defend(ActionId("d1")), GameCommand.Attack(ActionId("a1"))).forEach { old ->
            assertTrue(rules.process(c, old, NoDice, 1L) is RuleResult.Replayed)
        }
        // And the fight carries on under the new rules.
        val after = apply(c, GameCommand.Attack(ActionId("a2")), ScriptedDice(20, 4, 6, 100, 100, 100))
        assertEquals(EncounterStatus.VICTORY, after.encounter!!.status)
        assertEquals(SkillCatalog.MELEE, after.rolls.last().skill)
    }

    @Test
    fun aCompletedVictoryInAnOldSaveCannotBeAppliedTwiceThroughTheStore() {
        val dir = temp.newFolder()
        File(dir, "combat-v1.json").writeText(fixture("campaign-v1-after-victory.json"))
        val store = FileCampaignStore(dir)
        val processor = ActionProcessor(store, rules, clock = { 5_000L })
        val id = CampaignId("combat-v1")
        val before = (store.load(id) as LoadResult.Loaded).campaign
        assertEquals(21, before.player.gold)

        // The exact command that won the fight in that save. Its fingerprint must still match.
        val again = processor.submit(id, GameCommand.Attack(ActionId("a2")))
        assertTrue("got $again", again is Submission.Replayed)
        val loot = (again as Submission.Replayed).events.payloads<EventPayload.EncounterEnded>().single()
        assertEquals(6, loot.gold)

        val reloaded = (store.load(id) as LoadResult.Loaded).campaign
        assertEquals("no second payout", before, reloaded)
        // A new attack on a finished fight is refused, not a second victory.
        assertTrue(processor.submit(id, GameCommand.Attack(ActionId("a3"))) is Submission.Rejected)
    }

    // ---------------------------------------------------------------- narration facts

    @Test
    fun outcomeFactsDescribeAWholeRoundWithNames() {
        val c = fight(rat)
        // Hit 15 (total 18), dagger 1 + 3 = 4 kills the rat.
        val after = apply(c, GameCommand.Attack(ActionId("a1")), ScriptedDice(15, 1, 1))
        val facts = OutcomeFacts.from(after, after.newEvents(c))
        assertEquals(
            listOf(
                OutcomeFact.AttackRolled("Giant rat", 15, 18, 10, hit = true),
                OutcomeFact.EnemyHurt("Giant rat", 4, 0, bySpell = false),
                OutcomeFact.FightEnded(EncounterStatus.VICTORY, 0, emptyList()),
                OutcomeFact.XpGained("Melee", 5, 1, 1),
            ),
            facts,
        )
    }

    @Test
    fun outcomeFactsReportEnemyTurnsAndIdentifyContents() {
        val c = fight(goblin)
        val after = apply(c, GameCommand.Cast(ActionId("i1"), SpellCatalog.IDENTIFY), ScriptedDice(20, 2))
        val facts = OutcomeFacts.from(after, after.newEvents(c))
        val entry = facts[0] as OutcomeFact.CreatureIdentified
        assertEquals("Goblin", entry.creature)
        assertTrue(entry.learned)
        assertEquals(
            listOf(OutcomeFact.PossibleDrop("Goblin ear", 60), OutcomeFact.PossibleDrop("Dagger", 20), OutcomeFact.PossibleDrop("Copper ring", 10)),
            entry.possibleDrops,
        )
        assertEquals(OutcomeFact.EnemyAttacked("Goblin", 20, 23, 12, hit = true), facts[1])
        assertEquals(OutcomeFact.PlayerHurt("Goblin", 2, c.player.vitals.hp - 2), facts[2])
    }

    @Test
    fun twoOfTheSameKindAreNumbered() {
        var c = rules.newCampaign(CampaignId("pair"), 42L, Fixtures.character(), FallbackRegion.proposal, 1_000L).valid()
        c = apply(c, GameCommand.Travel(ActionId("t1"), c.locationNamed("Thornwick").id))
        c = apply(c, GameCommand.Travel(ActionId("t2"), c.locationNamed("Bramblewood").id))
        val after = apply(c, GameCommand.StartEncounter(ActionId("e1"), listOf(rat, rat)))
        val started = OutcomeFacts.from(after, after.newEvents(c)).single() as OutcomeFact.FightStarted
        assertEquals(listOf("Giant rat 1", "Giant rat 2"), started.enemies.map { it.name })
    }
}

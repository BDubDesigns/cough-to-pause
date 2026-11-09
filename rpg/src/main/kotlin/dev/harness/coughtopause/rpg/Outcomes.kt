package dev.harness.coughtopause.rpg

/**
 * One mechanical result of a committed action, with every id already resolved to a name.
 *
 * This is the single interpretation of [EventPayload]s that every narrator reads: the scripted
 * narrator turns facts into spoken lines, the AI prompt turns the same facts into "engine results
 * (authoritative)". Neither re-reads raw events, so they cannot disagree about what happened, and a
 * new event type is described in exactly one place ([OutcomeFacts.from]).
 *
 * Facts carry numbers, never prose. Wording belongs to the renderer.
 */
sealed interface OutcomeFact {
    /** A skill check (not an attack). [natural] is null when no die was rolled. */
    data class CheckRolled(
        val skill: String,
        val intent: String,
        val natural: Int?,
        val total: Int?,
        val dc: Int?,
        val outcome: CheckOutcome,
    ) : OutcomeFact

    /** The player's attack roll against an enemy's defense. */
    data class AttackRolled(val target: String, val natural: Int, val total: Int, val defense: Int, val hit: Boolean) : OutcomeFact

    data class XpGained(val skill: String, val amount: Int, val levelBefore: Int, val levelAfter: Int) : OutcomeFact

    /** XP was considered and withheld (a repeated challenge, no roll needed, the cap). */
    data class XpWithheld(val skill: String, val reason: XpReason) : OutcomeFact

    data class Travelled(val destination: String) : OutcomeFact

    data class GoldSpent(val amount: Int, val purpose: String, val balanceAfter: Int) : OutcomeFact

    /** A world fact was committed (an NPC told the player something). */
    data class FactLearned(val text: String) : OutcomeFact

    data class FightStarted(val enemies: List<EnemyState>) : OutcomeFact

    data class EnemyState(val name: String, val hp: Int, val maxHp: Int)

    /** An enemy swung at the player. Followed by [PlayerHurt] when [hit]. */
    data class EnemyAttacked(val attacker: String, val natural: Int, val total: Int, val defense: Int, val hit: Boolean) : OutcomeFact

    data class PlayerHurt(val attacker: String, val amount: Int, val hpAfter: Int) : OutcomeFact

    /** The player's blow or spell landed. [bySpell] tells a spell from a weapon. */
    data class EnemyHurt(val target: String, val amount: Int, val hpAfter: Int, val bySpell: Boolean) : OutcomeFact {
        val felled: Boolean get() = hpAfter <= 0
    }

    data class Guarded(val bonus: Int) : OutcomeFact

    data class SpellCast(val spell: String, val target: String?, val mpAfter: Int) : OutcomeFact

    /**
     * Identify's result: the catalogue entry, which is what the spell reveals. [learned] false means
     * the entry was already known and reading it cost nothing.
     */
    data class CreatureIdentified(
        val creature: String,
        val learned: Boolean,
        val mpAfter: Int,
        val level: Int,
        val threat: ThreatTier,
        val maxHp: Int,
        val possibleDrops: List<PossibleDrop>,
    ) : OutcomeFact

    data class PossibleDrop(val item: String, val chancePercent: Int)

    data class ItemUsed(val item: String, val hpAfter: Int, val remaining: Int) : OutcomeFact

    /** End of a fight. [loot] is item name → count, in the order the engine granted it. */
    data class FightEnded(val status: EncounterStatus, val gold: Int, val loot: List<Pair<String, Int>>) : OutcomeFact

    data class Recovered(val location: String, val hp: Int, val goldLost: Int) : OutcomeFact
}

object OutcomeFacts {

    /**
     * Facts for [events], in event order. Names come from [campaign] (locations, world facts, the
     * encounter log) and [catalog] (creatures, items, spells) — never from the narrator.
     */
    fun from(campaign: Campaign, events: List<GameEvent>, catalog: ContentCatalog = ContentCatalog.STARTER): List<OutcomeFact> {
        val names = EnemyNames(campaign.events + events, catalog)
        val facts = mutableListOf<OutcomeFact>()
        for (event in events) {
            facts += when (val p = event.payload) {
                is EventPayload.CampaignStarted -> continue
                is EventPayload.CheckResolved -> {
                    val roll = p.roll
                    if (roll.challenge.topic == Rules.ATTACK_TOPIC && roll.natural != null) {
                        OutcomeFact.AttackRolled(
                            target = names[roll.challenge.subjectId],
                            natural = roll.natural,
                            total = roll.total ?: roll.natural,
                            defense = roll.difficultyClass ?: 0,
                            hit = roll.outcome == CheckOutcome.SUCCESS,
                        )
                    } else {
                        OutcomeFact.CheckRolled(
                            skill = skillName(roll.skill),
                            intent = roll.intent,
                            natural = roll.natural,
                            total = roll.total,
                            dc = roll.difficultyClass,
                            outcome = roll.outcome,
                        )
                    }
                }
                is EventPayload.SkillXp ->
                    if (p.amount > 0) {
                        OutcomeFact.XpGained(skillName(p.skill), p.amount, p.levelBefore, p.levelAfter)
                    } else {
                        OutcomeFact.XpWithheld(skillName(p.skill), p.reason)
                    }
                is EventPayload.Travelled -> OutcomeFact.Travelled(locationName(campaign, p.to))
                is EventPayload.GoldSpent -> OutcomeFact.GoldSpent(p.amount, p.purpose, p.balanceAfter)
                is EventPayload.WorldContentAccepted -> {
                    p.facts.mapNotNull { id -> campaign.world.facts.firstOrNull { it.id == id } }
                        .forEach { facts += OutcomeFact.FactLearned(it.text) }
                    continue
                }
                is EventPayload.EncounterStarted -> OutcomeFact.FightStarted(
                    p.enemies.map { OutcomeFact.EnemyState(names[it.id], it.hp, it.maxHp) },
                )
                is EventPayload.EnemyAttacked ->
                    OutcomeFact.EnemyAttacked(names[p.attackerId], p.natural, p.total, p.defense, p.hit)
                is EventPayload.DamageDealt ->
                    if (p.targetIsPlayer) {
                        OutcomeFact.PlayerHurt(names[p.attackerId], p.amount, p.hpAfter)
                    } else {
                        OutcomeFact.EnemyHurt(names[p.targetId], p.amount, p.hpAfter, bySpell = p.skill == SkillCatalog.MAGIC)
                    }
                is EventPayload.Defended -> OutcomeFact.Guarded(p.bonus)
                is EventPayload.SpellCast -> OutcomeFact.SpellCast(
                    spell = catalog.spell(p.spell)?.name ?: p.spell.value,
                    target = p.targetId?.let { names[it] },
                    mpAfter = p.mpAfter,
                )
                is EventPayload.CreatureIdentified -> {
                    val template = catalog.creature(p.template)
                    OutcomeFact.CreatureIdentified(
                        creature = template?.name ?: p.template.value,
                        learned = p.learned,
                        mpAfter = p.mpAfter,
                        level = template?.level ?: 0,
                        threat = template?.threat ?: ThreatTier.STANDARD,
                        maxHp = template?.maxHp ?: 0,
                        possibleDrops = template?.loot.orEmpty().map {
                            OutcomeFact.PossibleDrop(itemName(catalog, it.item), it.chancePercent)
                        },
                    )
                }
                is EventPayload.ItemUsed -> OutcomeFact.ItemUsed(itemName(catalog, p.item), p.hpAfter, p.remaining)
                is EventPayload.EncounterEnded -> OutcomeFact.FightEnded(
                    status = p.status,
                    gold = p.gold,
                    loot = p.items.groupingBy { itemName(catalog, it) }.eachCount().toList(),
                )
                is EventPayload.Recovered -> OutcomeFact.Recovered(locationName(campaign, p.location), p.hp, p.goldLost)
            }
        }
        return facts
    }

    private fun skillName(id: SkillId): String = SkillCatalog[id]?.name ?: id.value

    private fun itemName(catalog: ContentCatalog, id: ItemTemplateId): String = catalog.item(id)?.name ?: id.value

    private fun locationName(campaign: Campaign, id: LocationId): String = campaign.world.locations[id]?.name ?: id.value

    /**
     * Creature instance id → spoken name, from every EncounterStarted in the log. Two of the same
     * kind in one fight are numbered ("Goblin 1", "Goblin 2") so a line never means either.
     */
    private class EnemyNames(log: List<GameEvent>, catalog: ContentCatalog) {
        private val byId: Map<String, String> = buildMap {
            log.mapNotNull { it.payload as? EventPayload.EncounterStarted }.forEach { started ->
                val counts = started.enemies.groupingBy { it.template }.eachCount()
                val seen = mutableMapOf<CreatureTemplateId, Int>()
                started.enemies.forEach { enemy ->
                    val base = catalog.creature(enemy.template)?.name ?: enemy.template.value
                    val n = seen.merge(enemy.template, 1, Int::plus)!!
                    put(enemy.id, if ((counts[enemy.template] ?: 0) > 1) "$base $n" else base)
                }
            }
        }

        operator fun get(id: String): String = byId[id] ?: id
    }
}

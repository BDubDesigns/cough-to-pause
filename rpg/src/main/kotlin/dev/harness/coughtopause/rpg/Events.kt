package dev.harness.coughtopause.rpg

/**
 * One committed, significant change. The event log explains every mechanical outcome and is what
 * narration should report from: replaying a narration line re-reads events, it never re-runs rules.
 */
data class GameEvent(
    val id: EventId,
    /** Position in the campaign's event log, 0-based, gap-free. */
    val sequence: Int,
    /** The action that caused it; null for campaign creation. */
    val actionId: ActionId?,
    val atMs: Long,
    val payload: EventPayload,
)

sealed interface EventPayload {
    data class CampaignStarted(
        val characterName: String,
        val background: BackgroundId,
        val startLocation: LocationId,
    ) : EventPayload

    data class WorldContentAccepted(
        val locations: List<LocationId>,
        val npcs: List<NpcId>,
        val facts: List<FactId>,
    ) : EventPayload

    data class CheckResolved(val roll: RollRecord) : EventPayload

    /** Recorded whether or not XP was granted, so withheld XP is explainable too. */
    data class SkillXp(
        val skill: SkillId,
        val amount: Int,
        val totalXp: Int,
        val levelBefore: Int,
        val levelAfter: Int,
        val reason: XpReason,
    ) : EventPayload

    data class GoldSpent(val amount: Int, val purpose: String, val balanceAfter: Int) : EventPayload

    data class Travelled(val from: LocationId, val to: LocationId) : EventPayload

    data class EncounterStarted(val encounterId: EncounterId, val enemies: List<CreatureInstance>) : EventPayload

    /** One landed blow, either direction. [hpAfter] is the defender's remaining hit points. */
    data class DamageDealt(
        val attackerId: String,
        val targetId: String,
        val amount: Int,
        val hpAfter: Int,
        val targetIsPlayer: Boolean,
        /**
         * The player skill that dealt it (Melee for a weapon, Magic for a spell); null for an enemy's
         * blow, and in saves written before combat XP existed. Victory XP goes to the felling skill.
         */
        val skill: SkillId? = null,
    ) : EventPayload

    /**
     * One enemy swing, recorded whether it lands or not, so a miss is as explainable as a hit.
     * A hit is followed by a [DamageDealt] with `targetIsPlayer = true`.
     */
    data class EnemyAttacked(
        val attackerId: String,
        val natural: Int,
        val attackBonus: Int,
        val total: Int,
        /** The player's defense at the time, guard included. */
        val defense: Int,
        val hit: Boolean,
    ) : EventPayload

    data class Defended(val bonus: Int) : EventPayload

    data class SpellCast(val spell: SpellId, val targetId: String?, val mpAfter: Int) : EventPayload

    /** Identify: [learned] false means the entry was already known, so it cost nothing. */
    data class CreatureIdentified(
        val template: CreatureTemplateId,
        val learned: Boolean,
        val mpAfter: Int,
    ) : EventPayload

    data class ItemUsed(val item: ItemTemplateId, val hpAfter: Int, val remaining: Int) : EventPayload

    /** End of a fight. Loot is granted here and only here, once per encounter. */
    data class EncounterEnded(
        val status: EncounterStatus,
        val gold: Int = 0,
        val items: List<ItemTemplateId> = emptyList(),
    ) : EventPayload

    /** Bounded defeat consequence: a safe location, restored HP, and the gold that was lost. */
    data class Recovered(val location: LocationId, val hp: Int, val goldLost: Int) : EventPayload
}

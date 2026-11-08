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
}

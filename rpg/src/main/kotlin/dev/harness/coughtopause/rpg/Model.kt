package dev.harness.coughtopause.rpg

/*
 * Authoritative campaign state. Immutable values: the only way to get a changed Campaign is
 * through Rules, which validates the command first and records an event for every change.
 */

enum class LocationKind {
    SETTLEMENT,
    INN,
    WILDERNESS,
    DUNGEON,
    LANDMARK,
}

data class Location(
    val id: LocationId,
    val name: String,
    val kind: LocationKind,
    val description: String,
    /** Undirected travel graph: if A connects to B, B connects to A. */
    val connections: Set<LocationId>,
    /** Catalog creatures that can be encountered here. Encounters elsewhere are refused. */
    val creatures: List<CreatureTemplateId> = emptyList(),
)

data class Npc(
    val id: NpcId,
    val name: String,
    val role: String,
    val description: String,
    val locationId: LocationId,
)

/** A committed piece of world truth. Never edited or removed once accepted. */
data class WorldFact(val id: FactId, val subjectId: String, val text: String)

data class WorldState(
    val locations: Map<LocationId, Location> = emptyMap(),
    val npcs: Map<NpcId, Npc> = emptyMap(),
    val facts: List<WorldFact> = emptyList(),
    /** Next number for a generated entity id. Monotonic: ids are never reused. */
    val nextEntityNumber: Int = 1,
) {
    fun containsEntity(id: String): Boolean =
        LocationId(id) in locations || NpcId(id) in npcs || facts.any { it.id.value == id }
}

data class Vitals(val hp: Int, val maxHp: Int, val mp: Int, val maxMp: Int) {
    init {
        require(maxHp > 0 && hp in 0..maxHp) { "hp $hp outside 0..$maxHp" }
        require(maxMp >= 0 && mp in 0..maxMp) { "mp $mp outside 0..$maxMp" }
    }
}

data class PlayerCharacter(
    val id: CharacterId,
    val name: String,
    val background: BackgroundId,
    val attributes: AttributeScores,
    /** XP for every catalog skill (0 = level 1). Every skill is present and trainable. */
    val skillXp: Map<SkillId, Int>,
    val vitals: Vitals,
    val gold: Int,
    /** Item template → count. Counts are always ≥ 1; absent means none. */
    val inventory: Map<ItemTemplateId, Int>,
) {
    init {
        require(gold >= 0) { "gold cannot be negative" }
        require(inventory.values.all { it > 0 }) { "inventory counts must be positive" }
        require(skillXp.values.all { it >= 0 }) { "skill xp cannot be negative" }
    }
}

enum class EncounterStatus {
    ACTIVE,
    VICTORY,
    RETREATED,
    DEFEAT,
}

data class CreatureInstance(val id: String, val template: CreatureTemplateId, val hp: Int, val maxHp: Int)

/**
 * Whose turn it is. When the player is the actor and [awaitingPlayerDecision] is true, nothing
 * happens until a typed command arrives: silence never selects an action.
 */
data class TurnState(val actor: String, val awaitingPlayerDecision: Boolean) {
    companion object {
        const val PLAYER = "player"
    }
}

data class Encounter(
    val id: EncounterId,
    val locationId: LocationId,
    val enemies: List<CreatureInstance>,
    val round: Int,
    val turn: TurnState,
    val status: EncounterStatus,
)

/** Idempotency receipt: an action id is applied at most once, and its payload is pinned. */
data class AppliedAction(
    val actionId: ActionId,
    /** SHA-256 of the canonical command; a reused id with a different payload is refused. */
    val fingerprint: String,
    val ordinal: Int,
    val committedAtMs: Long,
    val eventIds: List<EventId>,
)

data class Campaign(
    val id: CampaignId,
    val seed: Long,
    val createdAtMs: Long,
    val day: Int,
    val currentLocationId: LocationId,
    val player: PlayerCharacter,
    val world: WorldState,
    val encounter: Encounter?,
    val xpLedger: Map<LedgerKey, Int>,
    val appliedActions: Map<ActionId, AppliedAction>,
    val events: List<GameEvent>,
) {
    /** Full roll history, oldest first, derived from the event log (single source of truth). */
    val rolls: List<RollRecord>
        get() = events.mapNotNull { (it.payload as? EventPayload.CheckResolved)?.roll }

    fun eventsFor(actionId: ActionId): List<GameEvent> = events.filter { it.actionId == actionId }

    /** Anything a challenge may legitimately be about. */
    fun knowsSubject(id: String): Boolean =
        world.containsEntity(id) ||
            encounter?.let { e -> e.id.value == id || e.enemies.any { it.id == id } } == true
}

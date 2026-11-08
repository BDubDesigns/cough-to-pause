package dev.harness.coughtopause.rpg

/*
 * Stable identifiers. Every authoritative thing the narrator can talk about has one of these,
 * and none of them is ever reassigned once committed to a campaign.
 */

/** Campaign ids double as file names, so they are restricted to a safe alphabet. */
@JvmInline
value class CampaignId(val value: String) {
    init {
        require(SAFE_ID.matches(value)) { "Campaign id must match ${SAFE_ID.pattern}: '$value'" }
    }

    override fun toString(): String = value
}

/**
 * Idempotency key for one player decision. The orchestrator mints it once, when an intention is
 * accepted, and reuses it for every retry/replay of that decision.
 */
@JvmInline
value class ActionId(val value: String) {
    init {
        require(value.isNotBlank() && value.length <= 128) { "Action id must be 1..128 non-blank chars" }
    }

    override fun toString(): String = value
}

@JvmInline value class EventId(val value: String) { override fun toString(): String = value }

@JvmInline value class RollId(val value: String) { override fun toString(): String = value }

@JvmInline value class CharacterId(val value: String) { override fun toString(): String = value }

@JvmInline value class SkillId(val value: String) { override fun toString(): String = value }

@JvmInline value class BackgroundId(val value: String) { override fun toString(): String = value }

@JvmInline value class ItemTemplateId(val value: String) { override fun toString(): String = value }

@JvmInline value class CreatureTemplateId(val value: String) { override fun toString(): String = value }

@JvmInline value class LocationId(val value: String) { override fun toString(): String = value }

@JvmInline value class NpcId(val value: String) { override fun toString(): String = value }

@JvmInline value class FactId(val value: String) { override fun toString(): String = value }

@JvmInline value class EncounterId(val value: String) { override fun toString(): String = value }

internal val SAFE_ID = Regex("[a-z0-9][a-z0-9_-]{0,63}")

/** Every rule violation is a typed value with a human-readable message, never an exception. */
interface DomainError {
    val message: String
}

/** Result of validating untrusted input: either a value, or every reason it was refused. */
sealed interface Validated<out T, out E : DomainError> {
    data class Valid<T>(val value: T) : Validated<T, Nothing>
    data class Invalid<E : DomainError>(val errors: List<E>) : Validated<Nothing, E> {
        init {
            require(errors.isNotEmpty()) { "Invalid needs at least one error" }
        }
    }
}

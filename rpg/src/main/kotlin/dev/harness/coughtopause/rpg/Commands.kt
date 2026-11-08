package dev.harness.coughtopause.rpg

/**
 * The only ways to change a campaign. Each carries the [ActionId] that makes it idempotent.
 * There is deliberately no "grant XP", "add item", "set HP" or "add gold" command: rewards are
 * consequences computed by the rules, never requests.
 */
sealed interface GameCommand {
    val actionId: ActionId

    /** Attempt something uncertain. Difficulty is part of the command, fixed before the roll. */
    data class AttemptCheck(override val actionId: ActionId, val request: CheckRequest) : GameCommand

    /** Move along an existing connection from the current location. */
    data class Travel(override val actionId: ActionId, val destination: LocationId) : GameCommand

    /** Pay for something (inn rest, a bribe). Prices are decided by the caller's rules, not here. */
    data class SpendGold(override val actionId: ActionId, val amount: Int, val purpose: String) : GameCommand

    /** Begin a fight with creatures native to the current location. */
    data class StartEncounter(override val actionId: ActionId, val creatures: List<CreatureTemplateId>) : GameCommand

    /** Admit validated AI-proposed content into the persistent world. */
    data class CommitWorldProposal(override val actionId: ActionId, val proposal: WorldProposal) : GameCommand
}

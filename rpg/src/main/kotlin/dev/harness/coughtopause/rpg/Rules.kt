package dev.harness.coughtopause.rpg

sealed interface CommandError : DomainError {
    data class ActionIdConflict(val actionId: ActionId) : CommandError {
        override val message get() = "Action '$actionId' was already applied with a different payload"
    }

    data class UnknownChallengeSubject(val subjectId: String) : CommandError {
        override val message get() = "Challenge subject '$subjectId' does not exist in this campaign"
    }

    data class UnknownLocation(val id: LocationId) : CommandError {
        override val message get() = "No location '$id'"
    }

    data class NotConnected(val from: LocationId, val to: LocationId) : CommandError {
        override val message get() = "No route from $from to $to"
    }

    data object AlreadyThere : CommandError {
        override val message get() = "Already at that location"
    }

    data object EncounterInProgress : CommandError {
        override val message get() = "Not allowed while an encounter is active"
    }

    data class InvalidAmount(val amount: Int) : CommandError {
        override val message get() = "Amount $amount must be 1..$MAX_GOLD_PER_ACTION"
    }

    data class InsufficientGold(val needed: Int, val available: Int) : CommandError {
        override val message get() = "Needs $needed gold, has $available"
    }

    data class InvalidText(val field: String) : CommandError {
        override val message get() = "$field must be 1..80 characters"
    }

    data class UnknownCreature(val id: CreatureTemplateId) : CommandError {
        override val message get() = "Creature '$id' is not in the catalog"
    }

    data class CreatureNotNative(val id: CreatureTemplateId, val location: LocationId) : CommandError {
        override val message get() = "Creature '$id' does not live at $location"
    }

    data class InvalidEnemyCount(val count: Int, val max: Int) : CommandError {
        override val message get() = "Encounter needs 1..$max enemies, got $count"
    }
}

const val MAX_GOLD_PER_ACTION = 100_000

sealed interface RuleResult {
    /** New state plus exactly the events this command produced. */
    data class Applied(val campaign: Campaign, val events: List<GameEvent>) : RuleResult

    /** Action id already committed with the same payload: nothing changes, original events returned. */
    data class Replayed(val events: List<GameEvent>) : RuleResult

    /** Refused; the campaign is untouched. */
    data class Rejected(val errors: List<DomainError>) : RuleResult
}

/**
 * The authoritative, pure rules reducer. No I/O, no clock, no hidden randomness: given the same
 * campaign, command, dice and time it always produces the same result.
 */
class Rules(
    val balance: BalanceConfig = BalanceConfig.DEFAULT,
    val catalog: ContentCatalog = ContentCatalog.STARTER,
) {
    private val checks = CheckEngine(balance.checks, catalog, balance.xpCurve)
    private val xpPolicy = XpAwardPolicy(balance.xpRewards, balance.xpCurve)
    private val proposals = WorldProposalValidator(balance.world, catalog)

    /** Builds a new campaign from a created character and a (validated here) initial region. */
    fun newCampaign(
        id: CampaignId,
        seed: Long,
        character: PlayerCharacter,
        region: WorldProposal,
        nowMs: Long,
    ): Validated<Campaign, DomainError> {
        val errors = proposals.validate(region, WorldState(), initialRegion = true)
        if (errors.isNotEmpty()) return Validated.Invalid(errors)
        val committed = WorldCommitter.commit(WorldState(), region)
        val start = checkNotNull(committed.startLocation)
        val started = EventPayload.CampaignStarted(character.name, character.background, start)
        val accepted = EventPayload.WorldContentAccepted(committed.locations, committed.npcs, committed.facts)
        val events = listOf(started, accepted).mapIndexed { i, payload -> event(i, null, nowMs, payload) }
        return Validated.Valid(
            Campaign(
                id = id,
                seed = seed,
                createdAtMs = nowMs,
                day = 1,
                currentLocationId = start,
                player = character,
                world = committed.world,
                encounter = null,
                xpLedger = emptyMap(),
                appliedActions = emptyMap(),
                events = events,
            ),
        )
    }

    fun process(campaign: Campaign, command: GameCommand, rng: RandomSource, nowMs: Long): RuleResult {
        val fingerprint = CommandCodec.fingerprint(command)
        campaign.appliedActions[command.actionId]?.let { prior ->
            return if (prior.fingerprint == fingerprint) {
                RuleResult.Replayed(campaign.eventsFor(command.actionId))
            } else {
                RuleResult.Rejected(listOf(CommandError.ActionIdConflict(command.actionId)))
            }
        }
        val tx = Transaction(campaign, command.actionId, nowMs)
        val errors: List<DomainError> = when (command) {
            is GameCommand.AttemptCheck -> attemptCheck(tx, command, rng)
            is GameCommand.Travel -> travel(tx, command)
            is GameCommand.SpendGold -> spendGold(tx, command)
            is GameCommand.StartEncounter -> startEncounter(tx, command)
            is GameCommand.CommitWorldProposal -> commitProposal(tx, command)
        }
        if (errors.isNotEmpty()) return RuleResult.Rejected(errors)
        return RuleResult.Applied(tx.commit(fingerprint), tx.events)
    }

    private fun attemptCheck(tx: Transaction, command: GameCommand.AttemptCheck, rng: RandomSource): List<DomainError> {
        val request = command.request
        if (!tx.state.knowsSubject(request.challenge.subjectId)) {
            return listOf(CommandError.UnknownChallengeSubject(request.challenge.subjectId))
        }
        val prepared = when (val v = checks.prepare(tx.state.player, request)) {
            is Validated.Invalid -> return v.errors
            is Validated.Valid -> v.value
        }
        val roll = checks.resolve(prepared, rng, RollId("roll-%06d".format(tx.nextSequence)), command.actionId)
        tx.emit(EventPayload.CheckResolved(roll))

        val player = tx.state.player
        val xpBefore = player.skillXp[request.skill] ?: 0
        val decision = xpPolicy.decide(request.skill, request.difficulty, roll.outcome, request.challenge, xpBefore, tx.state.xpLedger)
        val xpAfter = xpBefore + decision.amount
        if (decision.amount > 0) {
            val key = LedgerKey(request.skill, request.challenge.subjectId, request.challenge.topic)
            tx.state = tx.state.copy(
                player = player.copy(skillXp = player.skillXp + (request.skill to xpAfter)),
                xpLedger = tx.state.xpLedger + (key to ((tx.state.xpLedger[key] ?: 0) + decision.amount)),
            )
        }
        val curve = balance.xpCurve
        tx.emit(
            EventPayload.SkillXp(
                request.skill, decision.amount, xpAfter, curve.levelFor(xpBefore), curve.levelFor(xpAfter), decision.reason,
            ),
        )
        return emptyList()
    }

    private fun travel(tx: Transaction, command: GameCommand.Travel): List<DomainError> {
        val state = tx.state
        if (state.encounter?.status == EncounterStatus.ACTIVE) return listOf(CommandError.EncounterInProgress)
        val destination = state.world.locations[command.destination]
            ?: return listOf(CommandError.UnknownLocation(command.destination))
        if (destination.id == state.currentLocationId) return listOf(CommandError.AlreadyThere)
        val here = state.world.locations.getValue(state.currentLocationId)
        if (destination.id !in here.connections) return listOf(CommandError.NotConnected(here.id, destination.id))
        tx.state = state.copy(currentLocationId = destination.id)
        tx.emit(EventPayload.Travelled(here.id, destination.id))
        return emptyList()
    }

    private fun spendGold(tx: Transaction, command: GameCommand.SpendGold): List<DomainError> {
        val errors = mutableListOf<DomainError>()
        if (command.amount !in 1..MAX_GOLD_PER_ACTION) errors += CommandError.InvalidAmount(command.amount)
        if (command.purpose.isBlank() || command.purpose.length > 80) errors += CommandError.InvalidText("purpose")
        val player = tx.state.player
        if (command.amount > player.gold) errors += CommandError.InsufficientGold(command.amount, player.gold)
        if (errors.isNotEmpty()) return errors
        val after = player.gold - command.amount
        tx.state = tx.state.copy(player = player.copy(gold = after))
        tx.emit(EventPayload.GoldSpent(command.amount, command.purpose.trim(), after))
        return emptyList()
    }

    private fun startEncounter(tx: Transaction, command: GameCommand.StartEncounter): List<DomainError> {
        val state = tx.state
        if (state.encounter?.status == EncounterStatus.ACTIVE) return listOf(CommandError.EncounterInProgress)
        val count = command.creatures.size
        if (count !in 1..balance.maxEnemiesPerEncounter) {
            return listOf(CommandError.InvalidEnemyCount(count, balance.maxEnemiesPerEncounter))
        }
        val here = state.world.locations.getValue(state.currentLocationId)
        val errors = mutableListOf<DomainError>()
        command.creatures.distinct().forEach { id ->
            when {
                catalog.creature(id) == null -> errors += CommandError.UnknownCreature(id)
                id !in here.creatures -> errors += CommandError.CreatureNotNative(id, here.id)
            }
        }
        if (errors.isNotEmpty()) return errors
        val encounterId = EncounterId("enc-%06d".format(tx.nextSequence))
        val enemies = command.creatures.mapIndexed { i, id ->
            val template = catalog.creature(id)!!
            CreatureInstance("${encounterId.value}-${i + 1}", id, template.maxHp, template.maxHp)
        }
        // PROVISIONAL: the player acts first. Initiative rules are not designed yet.
        val encounter = Encounter(
            id = encounterId,
            locationId = here.id,
            enemies = enemies,
            round = 1,
            turn = TurnState(TurnState.PLAYER, awaitingPlayerDecision = true),
            status = EncounterStatus.ACTIVE,
        )
        tx.state = state.copy(encounter = encounter)
        tx.emit(EventPayload.EncounterStarted(encounterId, enemies))
        return emptyList()
    }

    private fun commitProposal(tx: Transaction, command: GameCommand.CommitWorldProposal): List<DomainError> {
        val errors = proposals.validate(command.proposal, tx.state.world, initialRegion = false)
        if (errors.isNotEmpty()) return errors
        val committed = WorldCommitter.commit(tx.state.world, command.proposal)
        tx.state = tx.state.copy(world = committed.world)
        tx.emit(EventPayload.WorldContentAccepted(committed.locations, committed.npcs, committed.facts))
        return emptyList()
    }

    /** Working copy for one command. Discarded on rejection; folded into the campaign on success. */
    private class Transaction(private val original: Campaign, private val actionId: ActionId, private val nowMs: Long) {
        var state: Campaign = original
        val events = mutableListOf<GameEvent>()

        val nextSequence: Int get() = original.events.size + events.size

        fun emit(payload: EventPayload) {
            events += event(nextSequence, actionId, nowMs, payload)
        }

        fun commit(fingerprint: String): Campaign {
            val receipt = AppliedAction(actionId, fingerprint, original.appliedActions.size, nowMs, events.map { it.id })
            return state.copy(
                events = original.events + events,
                appliedActions = original.appliedActions + (actionId to receipt),
            )
        }
    }

    private companion object {
        fun event(sequence: Int, actionId: ActionId?, nowMs: Long, payload: EventPayload) =
            GameEvent(EventId("evt-%06d".format(sequence)), sequence, actionId, nowMs, payload)
    }
}

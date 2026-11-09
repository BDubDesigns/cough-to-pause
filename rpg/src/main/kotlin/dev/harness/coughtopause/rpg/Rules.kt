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

    data object NotInEncounter : CommandError {
        override val message get() = "There is no fight here"
    }

    data object EncounterOver : CommandError {
        override val message get() = "That fight is already over"
    }

    data object NoLivingEnemy : CommandError {
        override val message get() = "Nothing left standing to fight"
    }

    data class UnknownTarget(val id: String) : CommandError {
        override val message get() = "No target '$id' here"
    }

    data class UnknownSpell(val id: SpellId) : CommandError {
        override val message get() = "Spell '$id' is not in the catalog"
    }

    data class InsufficientMp(val needed: Int, val available: Int) : CommandError {
        override val message get() = "Needs $needed MP, has $available"
    }

    data class UnknownItem(val id: ItemTemplateId) : CommandError {
        override val message get() = "Item '$id' is not in the catalog"
    }

    data class ItemNotCarried(val id: ItemTemplateId) : CommandError {
        override val message get() = "Not carrying '$id'"
    }

    data class NotConsumable(val id: ItemTemplateId) : CommandError {
        override val message get() = "'$id' is not something you can use up"
    }

    data object AlreadyAtFullHealth : CommandError {
        override val message get() = "Already at full health"
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
            is GameCommand.Attack -> attack(tx, command, rng)
            is GameCommand.Defend -> defend(tx, command, rng)
            is GameCommand.Cast -> cast(tx, command, rng)
            is GameCommand.UseItem -> useItem(tx, command, rng)
            is GameCommand.Retreat -> retreat(tx, command, rng)
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

    // ---------------------------------------------------------------- combat

    /**
     * One player action resolves the whole round: the blow lands (or misses), then every living
     * enemy swings once. Nothing here can be re-run: the action id makes the round idempotent, and
     * dice come from the per-action seeded [rng], so a retried commit cannot reroll it.
     */
    private fun attack(tx: Transaction, command: GameCommand.Attack, rng: RandomSource): List<DomainError> {
        val enc = requireLiveEncounter(tx) ?: return listOf(if (tx.state.encounter == null) CommandError.NotInEncounter else CommandError.EncounterOver)
        val target = resolveTarget(enc, command.targetId) ?: return listOf(CommandError.NoLivingEnemy)
        val template = catalog.creature(target.template) ?: return listOf(CommandError.UnknownCreature(target.template))
        val player = tx.state.player
        val strength = player.attributes.modifier(Attribute.STRENGTH)
        val natural = Dice.roll(rng, 20)
        val total = natural + strength
        val hit = total >= template.defense
        tx.emit(
            EventPayload.CheckResolved(
                attackRoll(tx, command.actionId, target, template, natural, strength, total, hit),
            ),
        )
        // Acting drops last round's guard.
        tx.state = tx.state.copy(encounter = enc.copy(playerGuard = 0))
        if (hit) {
            val dice = CombatMath.weaponDice(player, catalog, balance.combat)
            val damage = CombatMath.rollDamage(dice, strength, rng, balance.combat.minimumDamage)
            val hpAfter = maxOf(0, target.hp - damage)
            tx.state = tx.state.copy(encounter = tx.state.encounter!!.damage(target.id, hpAfter))
            tx.emit(EventPayload.DamageDealt(TurnState.PLAYER, target.id, damage, hpAfter, targetIsPlayer = false))
        }
        return finishPlayerTurn(tx, rng)
    }

    private fun defend(tx: Transaction, command: GameCommand.Defend, rng: RandomSource): List<DomainError> {
        val enc = requireLiveEncounter(tx)
            ?: return listOf(if (tx.state.encounter == null) CommandError.NotInEncounter else CommandError.EncounterOver)
        val bonus = balance.combat.guardBonus
        tx.state = tx.state.copy(encounter = enc.copy(playerGuard = bonus))
        tx.emit(EventPayload.Defended(bonus))
        return finishPlayerTurn(tx, rng)
    }

    private fun cast(tx: Transaction, command: GameCommand.Cast, rng: RandomSource): List<DomainError> {
        val spell = catalog.spell(command.spell) ?: return listOf(CommandError.UnknownSpell(command.spell))
        val player = tx.state.player
        return when (spell.kind) {
            SpellKind.DAMAGE -> {
                val enc = requireLiveEncounter(tx)
                    ?: return listOf(if (tx.state.encounter == null) CommandError.NotInEncounter else CommandError.EncounterOver)
                val target = resolveTarget(enc, command.targetId) ?: return listOf(CommandError.NoLivingEnemy)
                if (player.vitals.mp < spell.mpCost) {
                    return listOf(CommandError.InsufficientMp(spell.mpCost, player.vitals.mp))
                }
                val mpAfter = player.vitals.mp - spell.mpCost
                tx.state = tx.state.copy(
                    player = player.copy(vitals = player.vitals.copy(mp = mpAfter)),
                    encounter = enc.copy(playerGuard = 0),
                )
                tx.emit(EventPayload.SpellCast(spell.id, target.id, mpAfter))
                val intelligence = player.attributes.modifier(Attribute.INTELLIGENCE)
                val damage = CombatMath.rollDamage(spell.damage!!, intelligence, rng, balance.combat.minimumDamage)
                val hpAfter = maxOf(0, target.hp - damage)
                tx.state = tx.state.copy(encounter = tx.state.encounter!!.damage(target.id, hpAfter))
                tx.emit(EventPayload.DamageDealt(TurnState.PLAYER, target.id, damage, hpAfter, targetIsPlayer = false))
                finishPlayerTurn(tx, rng)
            }

            SpellKind.IDENTIFY -> {
                val templateId = resolveTemplate(tx.state, command.targetId)
                    ?: return listOf(CommandError.UnknownTarget(command.targetId ?: "?"))
                if (templateId in tx.state.identified) {
                    // Known already: free to read, and it does not cost the turn.
                    tx.emit(EventPayload.CreatureIdentified(templateId, learned = false, mpAfter = player.vitals.mp))
                    return emptyList()
                }
                if (player.vitals.mp < spell.mpCost) {
                    return listOf(CommandError.InsufficientMp(spell.mpCost, player.vitals.mp))
                }
                val mpAfter = player.vitals.mp - spell.mpCost
                tx.state = tx.state.copy(
                    player = player.copy(vitals = player.vitals.copy(mp = mpAfter)),
                    identified = tx.state.identified + templateId,
                )
                tx.emit(EventPayload.CreatureIdentified(templateId, learned = true, mpAfter = mpAfter))
                // Learning something in the middle of a fight still costs the turn.
                if (tx.state.encounter?.status == EncounterStatus.ACTIVE) finishPlayerTurn(tx, rng) else emptyList()
            }
        }
    }

    private fun useItem(tx: Transaction, command: GameCommand.UseItem, rng: RandomSource): List<DomainError> {
        catalog.item(command.item) ?: return listOf(CommandError.UnknownItem(command.item))
        val player = tx.state.player
        val held = player.inventory[command.item] ?: return listOf(CommandError.ItemNotCarried(command.item))
        val template = catalog.item(command.item)!!
        if (template.healsHp <= 0) return listOf(CommandError.NotConsumable(command.item))
        if (player.vitals.hp >= player.vitals.maxHp) return listOf(CommandError.AlreadyAtFullHealth)

        val hpAfter = minOf(player.vitals.maxHp, player.vitals.hp + template.healsHp)
        val inventory = player.inventory.toMutableMap()
        val remaining = held - 1
        if (remaining > 0) inventory[command.item] = remaining else inventory.remove(command.item)
        tx.state = tx.state.copy(
            player = player.copy(vitals = player.vitals.copy(hp = hpAfter), inventory = inventory),
        )
        tx.emit(EventPayload.ItemUsed(command.item, hpAfter, remaining))
        // Using a draught mid-fight is an action like any other.
        return if (tx.state.encounter?.status == EncounterStatus.ACTIVE) finishPlayerTurn(tx, rng) else emptyList()
    }

    private fun retreat(tx: Transaction, command: GameCommand.Retreat, rng: RandomSource): List<DomainError> {
        val enc = requireLiveEncounter(tx)
            ?: return listOf(if (tx.state.encounter == null) CommandError.NotInEncounter else CommandError.EncounterOver)
        // Mark it over first: a parting blow must not turn a retreat into a victory.
        tx.state = tx.state.copy(encounter = enc.copy(status = EncounterStatus.RETREATED, playerGuard = 0))
        if (enemyTurn(tx, rng)) {
            defeat(tx)
            return emptyList()
        }
        tx.emit(EventPayload.EncounterEnded(EncounterStatus.RETREATED))
        return emptyList()
    }

    /** The encounter if a combat action is legal right now; null otherwise. */
    private fun requireLiveEncounter(tx: Transaction): Encounter? =
        tx.state.encounter?.takeIf { it.status == EncounterStatus.ACTIVE }

    private fun resolveTarget(enc: Encounter, targetId: String?): CreatureInstance? =
        if (targetId == null) enc.livingEnemies.firstOrNull()
        else enc.livingEnemies.firstOrNull { it.id == targetId }

    /** An enemy id resolves through the encounter; otherwise treat the id as a creature template. */
    private fun resolveTemplate(campaign: Campaign, targetId: String?): CreatureTemplateId? {
        if (targetId == null) return null
        campaign.encounter?.enemies?.firstOrNull { it.id == targetId }?.let { return it.template }
        val asTemplate = CreatureTemplateId(targetId)
        return if (catalog.creature(asTemplate) != null) asTemplate else null
    }

    /** Enemies each swing once. Returns true when the player was reduced to 0 HP. */
    private fun enemyTurn(tx: Transaction, rng: RandomSource): Boolean {
        val enc = tx.state.encounter ?: return false
        for (enemy in enc.livingEnemies) {
            val template = catalog.creature(enemy.template) ?: continue
            val player = tx.state.player
            val defense = CombatMath.playerDefense(player, catalog, balance.combat, enc.playerGuard)
            val natural = Dice.roll(rng, 20)
            if (natural + template.attackBonus < defense) continue
            val damage = CombatMath.rollDamage(template.damage, 0, rng, balance.combat.minimumDamage)
            val hpAfter = maxOf(0, player.vitals.hp - damage)
            tx.state = tx.state.copy(player = player.copy(vitals = player.vitals.copy(hp = hpAfter)))
            tx.emit(EventPayload.DamageDealt(enemy.id, TurnState.PLAYER, damage, hpAfter, targetIsPlayer = true))
            if (hpAfter <= 0) return true
        }
        return false
    }

    private fun finishPlayerTurn(tx: Transaction, rng: RandomSource): List<DomainError> {
        val enc = tx.state.encounter ?: return emptyList()
        if (enc.livingEnemies.isEmpty()) return victory(tx, rng, enc)
        if (enemyTurn(tx, rng)) {
            defeat(tx)
            return emptyList()
        }
        val after = tx.state.encounter ?: return emptyList()
        tx.state = tx.state.copy(encounter = after.copy(round = after.round + 1, playerGuard = 0))
        return emptyList()
    }

    /** Loot is granted here and only here; it is the single place rewards enter a campaign. */
    private fun victory(tx: Transaction, rng: RandomSource, enc: Encounter): List<DomainError> {
        var gold = 0
        val loot = mutableListOf<ItemTemplateId>()
        enc.enemies.forEach { enemy ->
            val template = catalog.creature(enemy.template) ?: return@forEach
            gold += template.goldMin + rng.nextInt(template.goldMax - template.goldMin + 1)
            template.loot.forEach { entry ->
                if (rng.nextInt(100) < entry.chancePercent) {
                    val quantity = entry.minQuantity + rng.nextInt(entry.maxQuantity - entry.minQuantity + 1)
                    repeat(quantity) { loot += entry.item }
                }
            }
        }
        val player = tx.state.player
        val inventory = player.inventory.toMutableMap()
        loot.forEach { inventory.merge(it, 1, Int::plus) }
        tx.state = tx.state.copy(
            player = player.copy(gold = player.gold + gold, inventory = inventory),
            encounter = enc.copy(status = EncounterStatus.VICTORY, playerGuard = 0),
        )
        tx.emit(EventPayload.EncounterEnded(EncounterStatus.VICTORY, gold = gold, items = loot))
        return emptyList()
    }

    /** Bounded defeat: half HP, the campaign's start, a capped gold loss, save intact. */
    private fun defeat(tx: Transaction) {
        val player = tx.state.player
        val safe = startLocation(tx.state)
        val lost = minOf(balance.combat.defeatGoldLoss, player.gold)
        val hp = maxOf(1, player.vitals.maxHp / 2)
        tx.state = tx.state.copy(
            player = player.copy(vitals = player.vitals.copy(hp = hp), gold = player.gold - lost),
            currentLocationId = safe,
            encounter = tx.state.encounter?.copy(status = EncounterStatus.DEFEAT, playerGuard = 0),
        )
        tx.emit(EventPayload.Recovered(safe, hp, lost))
    }

    private fun startLocation(campaign: Campaign): LocationId =
        campaign.events.asReversed()
            .firstNotNullOfOrNull { (it.payload as? EventPayload.CampaignStarted)?.startLocation }
            ?: campaign.currentLocationId

    /** Attack rolls are audit records too: they show up in the same roll history as checks. */
    private fun attackRoll(
        tx: Transaction,
        actionId: ActionId,
        target: CreatureInstance,
        template: CreatureTemplate,
        natural: Int,
        modifier: Int,
        total: Int,
        hit: Boolean,
    ): RollRecord {
        val skill = SkillCatalog.ATHLETICS
        val xp = tx.state.player.skillXp[skill] ?: 0
        return RollRecord(
            id = RollId("roll-atk-%d".format(tx.nextSequence)),
            actionId = actionId,
            skill = skill,
            attribute = Attribute.STRENGTH,
            intent = "attack ${template.name}",
            challenge = ChallengeKey(target.id, "attack"),
            difficulty = DifficultyTier.MODERATE,
            difficultyClass = template.defense,
            natural = natural,
            attributeModifier = modifier,
            skillLevel = balance.xpCurve.levelFor(xp),
            skillBonus = 0,
            modifiers = emptyList(),
            total = total,
            outcome = if (hit) CheckOutcome.SUCCESS else CheckOutcome.FAILURE,
            explanation = "d20 %d %+d = %d vs defense %d".format(natural, modifier, total, template.defense),
            importance = RollImportance.IMPORTANT,
        )
    }

    private fun Encounter.damage(enemyId: String, hpAfter: Int): Encounter =
        copy(enemies = enemies.map { if (it.id == enemyId) it.copy(hp = hpAfter) else it })

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

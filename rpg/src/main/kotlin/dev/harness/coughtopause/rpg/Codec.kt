package dev.harness.coughtopause.rpg

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

sealed interface PersistenceError : DomainError {
    data class Corrupt(val detail: String) : PersistenceError {
        override val message get() = "Save data is unreadable: $detail"
    }

    data class UnsupportedSchema(val found: Int, val supported: Int) : PersistenceError {
        override val message get() = "Save schema v$found is newer than this app supports (v$supported)"
    }
}

/**
 * Campaign ⇄ JSON. Versioned: every document carries `schema`. Older documents are upgraded
 * step-by-step through [migrations] before decoding; newer ones are refused rather than guessed at,
 * so an old build can never silently drop fields written by a newer one.
 */
object CampaignCodec {
    const val SCHEMA_VERSION = 2

    /**
     * `from → transform to from+1`. To change the format: bump [SCHEMA_VERSION], add
     * `SCHEMA_VERSION - 1 to { json -> ... }`, and add a test that decodes a checked-in old document
     * (see `src/test/resources/saves`).
     *
     * v1 → v2 (combat): v2 adds `identified`, `encounter.playerGuard`, the combat event types and
     * `DamageDealt.skill`. Every v2 field is optional on read, so a v1 document needs no rewrite —
     * the bump exists so that a pre-combat build refuses a v2 save as "newer" instead of calling it
     * corrupt when it meets an event type it has never seen.
     */
    internal val migrations: Map<Int, (JSONObject) -> JSONObject> = mapOf(
        1 to { json -> json },
    )

    fun encode(c: Campaign): String = JSONObject().apply {
        put("schema", SCHEMA_VERSION)
        put("id", c.id.value)
        put("seed", c.seed)
        put("createdAtMs", c.createdAtMs)
        put("day", c.day)
        put("currentLocation", c.currentLocationId.value)
        put("player", player(c.player))
        put("world", world(c.world))
        put("encounter", c.encounter?.let { encounter(it) } ?: JSONObject.NULL)
        put("identified", JSONArray(c.identified.map { it.value }))
        put("xpLedger", array(c.xpLedger.entries) { (k, v) ->
            JSONObject().put("skill", k.skill.value).put("subject", k.subjectId).put("topic", k.topic).put("xp", v)
        })
        put("appliedActions", array(c.appliedActions.values) { a ->
            JSONObject()
                .put("actionId", a.actionId.value)
                .put("fingerprint", a.fingerprint)
                .put("ordinal", a.ordinal)
                .put("committedAtMs", a.committedAtMs)
                .put("eventIds", JSONArray(a.eventIds.map { it.value }))
        })
        put("events", array(c.events) { event(it) })
    }.toString()

    fun decode(text: String): Validated<Campaign, PersistenceError> {
        return try {
            var json = JSONObject(text)
            var version = json.getInt("schema")
            if (version > SCHEMA_VERSION) {
                return Validated.Invalid(listOf(PersistenceError.UnsupportedSchema(version, SCHEMA_VERSION)))
            }
            while (version < SCHEMA_VERSION) {
                val step = migrations[version]
                    ?: return Validated.Invalid(listOf(PersistenceError.Corrupt("no migration from schema v$version")))
                json = step(json)
                version++
            }
            Validated.Valid(campaign(json))
        } catch (e: Exception) {
            // JSONException for missing/mistyped fields, IllegalArgumentException from invariants.
            Validated.Invalid(listOf(PersistenceError.Corrupt(e.message ?: e.javaClass.simpleName)))
        }
    }

    // ---- encode ----

    private fun player(p: PlayerCharacter) = JSONObject()
        .put("id", p.id.value)
        .put("name", p.name)
        .put("background", p.background.value)
        .put("attributes", JSONObject().apply { p.attributes.values.forEach { (a, s) -> put(a.name, s) } })
        .put("skillXp", JSONObject().apply { p.skillXp.forEach { (k, v) -> put(k.value, v) } })
        .put("hp", p.vitals.hp).put("maxHp", p.vitals.maxHp)
        .put("mp", p.vitals.mp).put("maxMp", p.vitals.maxMp)
        .put("gold", p.gold)
        .put("inventory", JSONObject().apply { p.inventory.forEach { (k, v) -> put(k.value, v) } })

    private fun world(w: WorldState) = JSONObject()
        .put("nextEntityNumber", w.nextEntityNumber)
        .put("locations", array(w.locations.values) { l ->
            JSONObject()
                .put("id", l.id.value).put("name", l.name).put("kind", l.kind.name).put("description", l.description)
                .put("connections", JSONArray(l.connections.map { it.value }.sorted()))
                .put("creatures", JSONArray(l.creatures.map { it.value }))
        })
        .put("npcs", array(w.npcs.values) { n ->
            JSONObject().put("id", n.id.value).put("name", n.name).put("role", n.role)
                .put("description", n.description).put("location", n.locationId.value)
        })
        .put("facts", array(w.facts) { f -> JSONObject().put("id", f.id.value).put("subject", f.subjectId).put("text", f.text) })

    private fun encounter(e: Encounter) = JSONObject()
        .put("id", e.id.value)
        .put("location", e.locationId.value)
        .put("enemies", array(e.enemies) { creature(it) })
        .put("round", e.round)
        .put("turnActor", e.turn.actor)
        .put("awaitingPlayerDecision", e.turn.awaitingPlayerDecision)
        .put("status", e.status.name)
        .put("playerGuard", e.playerGuard)

    private fun creature(c: CreatureInstance) = JSONObject()
        .put("id", c.id).put("template", c.template.value).put("hp", c.hp).put("maxHp", c.maxHp)

    private fun event(e: GameEvent): JSONObject = JSONObject()
        .put("id", e.id.value)
        .put("sequence", e.sequence)
        .put("actionId", e.actionId?.value ?: JSONObject.NULL)
        .put("atMs", e.atMs)
        .put("payload", payload(e.payload))

    private fun payload(p: EventPayload): JSONObject = when (p) {
        is EventPayload.CampaignStarted -> JSONObject().put("type", "CampaignStarted")
            .put("characterName", p.characterName).put("background", p.background.value).put("startLocation", p.startLocation.value)
        is EventPayload.WorldContentAccepted -> JSONObject().put("type", "WorldContentAccepted")
            .put("locations", JSONArray(p.locations.map { it.value }))
            .put("npcs", JSONArray(p.npcs.map { it.value }))
            .put("facts", JSONArray(p.facts.map { it.value }))
        is EventPayload.CheckResolved -> JSONObject().put("type", "CheckResolved").put("roll", roll(p.roll))
        is EventPayload.SkillXp -> JSONObject().put("type", "SkillXp")
            .put("skill", p.skill.value).put("amount", p.amount).put("totalXp", p.totalXp)
            .put("levelBefore", p.levelBefore).put("levelAfter", p.levelAfter).put("reason", p.reason.name)
        is EventPayload.GoldSpent -> JSONObject().put("type", "GoldSpent")
            .put("amount", p.amount).put("purpose", p.purpose).put("balanceAfter", p.balanceAfter)
        is EventPayload.Travelled -> JSONObject().put("type", "Travelled").put("from", p.from.value).put("to", p.to.value)
        is EventPayload.EncounterStarted -> JSONObject().put("type", "EncounterStarted")
            .put("encounterId", p.encounterId.value).put("enemies", array(p.enemies) { creature(it) })
        is EventPayload.DamageDealt -> JSONObject().put("type", "DamageDealt")
            .put("attackerId", p.attackerId).put("targetId", p.targetId).put("amount", p.amount)
            .put("hpAfter", p.hpAfter).put("targetIsPlayer", p.targetIsPlayer)
            .put("skill", p.skill?.value ?: JSONObject.NULL)
        is EventPayload.EnemyAttacked -> JSONObject().put("type", "EnemyAttacked")
            .put("attackerId", p.attackerId).put("natural", p.natural).put("attackBonus", p.attackBonus)
            .put("total", p.total).put("defense", p.defense).put("hit", p.hit)
        is EventPayload.Defended -> JSONObject().put("type", "Defended").put("bonus", p.bonus)
        is EventPayload.SpellCast -> JSONObject().put("type", "SpellCast")
            .put("spell", p.spell.value).put("targetId", p.targetId ?: JSONObject.NULL).put("mpAfter", p.mpAfter)
        is EventPayload.CreatureIdentified -> JSONObject().put("type", "CreatureIdentified")
            .put("template", p.template.value).put("learned", p.learned).put("mpAfter", p.mpAfter)
        is EventPayload.ItemUsed -> JSONObject().put("type", "ItemUsed")
            .put("item", p.item.value).put("hpAfter", p.hpAfter).put("remaining", p.remaining)
        is EventPayload.EncounterEnded -> JSONObject().put("type", "EncounterEnded")
            .put("status", p.status.name).put("gold", p.gold)
            .put("items", JSONArray(p.items.map { it.value }))
        is EventPayload.Recovered -> JSONObject().put("type", "Recovered")
            .put("location", p.location.value).put("hp", p.hp).put("goldLost", p.goldLost)
    }

    private fun roll(r: RollRecord) = JSONObject()
        .put("id", r.id.value)
        .put("actionId", r.actionId.value)
        .put("skill", r.skill.value)
        .put("attribute", r.attribute.name)
        .put("intent", r.intent)
        .put("challenge", CommandCodec.challenge(r.challenge))
        .put("difficulty", r.difficulty.name)
        .put("dc", r.difficultyClass ?: JSONObject.NULL)
        .put("natural", r.natural ?: JSONObject.NULL)
        .put("attributeModifier", r.attributeModifier)
        .put("skillLevel", r.skillLevel)
        .put("skillBonus", r.skillBonus)
        .put("modifiers", array(r.modifiers) { CommandCodec.modifier(it) })
        .put("total", r.total ?: JSONObject.NULL)
        .put("outcome", r.outcome.name)
        .put("explanation", r.explanation)
        .put("importance", r.importance.name)

    // ---- decode ----

    private fun campaign(o: JSONObject) = Campaign(
        id = CampaignId(o.getString("id")),
        seed = o.getLong("seed"),
        createdAtMs = o.getLong("createdAtMs"),
        day = o.getInt("day"),
        currentLocationId = LocationId(o.getString("currentLocation")),
        player = player(o.getJSONObject("player")),
        world = world(o.getJSONObject("world")),
        encounter = if (o.isNull("encounter")) null else encounter(o.getJSONObject("encounter")),
        xpLedger = o.getJSONArray("xpLedger").objects().associate {
            LedgerKey(SkillId(it.getString("skill")), it.getString("subject"), it.getString("topic")) to it.getInt("xp")
        },
        appliedActions = o.getJSONArray("appliedActions").objects().associate {
            val id = ActionId(it.getString("actionId"))
            id to AppliedAction(
                actionId = id,
                fingerprint = it.getString("fingerprint"),
                ordinal = it.getInt("ordinal"),
                committedAtMs = it.getLong("committedAtMs"),
                eventIds = it.getJSONArray("eventIds").strings().map(::EventId),
            )
        },
        events = o.getJSONArray("events").objects().map { event(it) },
        // Absent in campaigns written before combat: an empty set reads back fine.
        identified = if (o.has("identified")) {
            o.getJSONArray("identified").strings().map(::CreatureTemplateId).toSet()
        } else {
            emptySet()
        },
    ).also { validateIntegrity(it) }

    /** Cross-references that a hand-edited or truncated file could break. */
    private fun validateIntegrity(c: Campaign) {
        require(c.currentLocationId in c.world.locations) { "current location ${c.currentLocationId} missing" }
        c.events.forEachIndexed { i, e -> require(e.sequence == i) { "event log has a gap at $i" } }
        val eventIds = c.events.map { it.id }.toSet()
        c.appliedActions.values.forEach { a ->
            require(a.eventIds.all { it in eventIds }) { "action ${a.actionId} references missing events" }
        }
        c.encounter?.let { require(it.locationId in c.world.locations) { "encounter location missing" } }
    }

    private fun player(o: JSONObject): PlayerCharacter {
        val attrs = o.getJSONObject("attributes")
        return PlayerCharacter(
            id = CharacterId(o.getString("id")),
            name = o.getString("name"),
            background = BackgroundId(o.getString("background")),
            attributes = AttributeScores(Attribute.entries.associateWith { attrs.getInt(it.name) }),
            skillXp = o.getJSONObject("skillXp").intMap().mapKeys { SkillId(it.key) },
            vitals = Vitals(o.getInt("hp"), o.getInt("maxHp"), o.getInt("mp"), o.getInt("maxMp")),
            gold = o.getInt("gold"),
            inventory = o.getJSONObject("inventory").intMap().mapKeys { ItemTemplateId(it.key) },
        )
    }

    private fun world(o: JSONObject) = WorldState(
        locations = o.getJSONArray("locations").objects().map {
            Location(
                id = LocationId(it.getString("id")),
                name = it.getString("name"),
                kind = LocationKind.valueOf(it.getString("kind")),
                description = it.getString("description"),
                connections = it.getJSONArray("connections").strings().map(::LocationId).toSet(),
                creatures = it.getJSONArray("creatures").strings().map(::CreatureTemplateId),
            )
        }.associateBy { it.id },
        npcs = o.getJSONArray("npcs").objects().map {
            Npc(NpcId(it.getString("id")), it.getString("name"), it.getString("role"), it.getString("description"), LocationId(it.getString("location")))
        }.associateBy { it.id },
        facts = o.getJSONArray("facts").objects().map { WorldFact(FactId(it.getString("id")), it.getString("subject"), it.getString("text")) },
        nextEntityNumber = o.getInt("nextEntityNumber"),
    )

    private fun encounter(o: JSONObject) = Encounter(
        id = EncounterId(o.getString("id")),
        locationId = LocationId(o.getString("location")),
        enemies = o.getJSONArray("enemies").objects().map { creature(it) },
        round = o.getInt("round"),
        turn = TurnState(o.getString("turnActor"), o.getBoolean("awaitingPlayerDecision")),
        status = EncounterStatus.valueOf(o.getString("status")),
        playerGuard = if (o.has("playerGuard")) o.getInt("playerGuard") else 0,
    )

    private fun creature(o: JSONObject) =
        CreatureInstance(o.getString("id"), CreatureTemplateId(o.getString("template")), o.getInt("hp"), o.getInt("maxHp"))

    private fun event(o: JSONObject) = GameEvent(
        id = EventId(o.getString("id")),
        sequence = o.getInt("sequence"),
        actionId = if (o.isNull("actionId")) null else ActionId(o.getString("actionId")),
        atMs = o.getLong("atMs"),
        payload = payload(o.getJSONObject("payload")),
    )

    private fun payload(o: JSONObject): EventPayload = when (val type = o.getString("type")) {
        "CampaignStarted" -> EventPayload.CampaignStarted(
            o.getString("characterName"), BackgroundId(o.getString("background")), LocationId(o.getString("startLocation")),
        )
        "WorldContentAccepted" -> EventPayload.WorldContentAccepted(
            o.getJSONArray("locations").strings().map(::LocationId),
            o.getJSONArray("npcs").strings().map(::NpcId),
            o.getJSONArray("facts").strings().map(::FactId),
        )
        "CheckResolved" -> EventPayload.CheckResolved(roll(o.getJSONObject("roll")))
        "SkillXp" -> EventPayload.SkillXp(
            SkillId(o.getString("skill")), o.getInt("amount"), o.getInt("totalXp"),
            o.getInt("levelBefore"), o.getInt("levelAfter"), XpReason.valueOf(o.getString("reason")),
        )
        "GoldSpent" -> EventPayload.GoldSpent(o.getInt("amount"), o.getString("purpose"), o.getInt("balanceAfter"))
        "Travelled" -> EventPayload.Travelled(LocationId(o.getString("from")), LocationId(o.getString("to")))
        "EncounterStarted" -> EventPayload.EncounterStarted(
            EncounterId(o.getString("encounterId")), o.getJSONArray("enemies").objects().map { creature(it) },
        )
        "DamageDealt" -> EventPayload.DamageDealt(
            o.getString("attackerId"), o.getString("targetId"), o.getInt("amount"),
            o.getInt("hpAfter"), o.getBoolean("targetIsPlayer"),
            // Absent before v2.
            skill = if (o.has("skill") && !o.isNull("skill")) SkillId(o.getString("skill")) else null,
        )
        "EnemyAttacked" -> EventPayload.EnemyAttacked(
            o.getString("attackerId"), o.getInt("natural"), o.getInt("attackBonus"),
            o.getInt("total"), o.getInt("defense"), o.getBoolean("hit"),
        )
        "Defended" -> EventPayload.Defended(o.getInt("bonus"))
        "SpellCast" -> EventPayload.SpellCast(
            SpellId(o.getString("spell")), if (o.isNull("targetId")) null else o.getString("targetId"),
            o.getInt("mpAfter"),
        )
        "CreatureIdentified" -> EventPayload.CreatureIdentified(
            CreatureTemplateId(o.getString("template")), o.getBoolean("learned"), o.getInt("mpAfter"),
        )
        "ItemUsed" -> EventPayload.ItemUsed(
            ItemTemplateId(o.getString("item")), o.getInt("hpAfter"), o.getInt("remaining"),
        )
        "EncounterEnded" -> EventPayload.EncounterEnded(
            EncounterStatus.valueOf(o.getString("status")), o.getInt("gold"),
            o.getJSONArray("items").strings().map(::ItemTemplateId),
        )
        "Recovered" -> EventPayload.Recovered(
            LocationId(o.getString("location")), o.getInt("hp"), o.getInt("goldLost"),
        )
        else -> throw IllegalArgumentException("unknown event type '$type'")
    }

    private fun roll(o: JSONObject) = RollRecord(
        id = RollId(o.getString("id")),
        actionId = ActionId(o.getString("actionId")),
        skill = SkillId(o.getString("skill")),
        attribute = Attribute.valueOf(o.getString("attribute")),
        intent = o.getString("intent"),
        challenge = CommandCodec.challenge(o.getJSONObject("challenge")),
        difficulty = DifficultyTier.valueOf(o.getString("difficulty")),
        difficultyClass = o.nullableInt("dc"),
        natural = o.nullableInt("natural"),
        attributeModifier = o.getInt("attributeModifier"),
        skillLevel = o.getInt("skillLevel"),
        skillBonus = o.getInt("skillBonus"),
        modifiers = o.getJSONArray("modifiers").objects().map { CommandCodec.modifier(it) },
        total = o.nullableInt("total"),
        outcome = CheckOutcome.valueOf(o.getString("outcome")),
        explanation = o.getString("explanation"),
        importance = RollImportance.valueOf(o.getString("importance")),
    )
}

/**
 * Canonical command encoding. Used to fingerprint an action so that a reused action id with a
 * different payload — e.g. the same attempt resubmitted at an easier difficulty — is refused.
 */
object CommandCodec {

    fun fingerprint(command: GameCommand): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(canonicalJson(encode(command)).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun encode(command: GameCommand): JSONObject = when (command) {
        is GameCommand.AttemptCheck -> base("AttemptCheck", command).put("request", request(command.request))
        is GameCommand.Travel -> base("Travel", command).put("destination", command.destination.value)
        is GameCommand.SpendGold -> base("SpendGold", command).put("amount", command.amount).put("purpose", command.purpose)
        is GameCommand.StartEncounter -> base("StartEncounter", command)
            .put("creatures", JSONArray(command.creatures.map { it.value }))
        is GameCommand.CommitWorldProposal -> base("CommitWorldProposal", command).put("proposal", proposal(command.proposal))
        is GameCommand.Attack -> base("Attack", command).put("target", command.targetId ?: JSONObject.NULL)
        is GameCommand.Defend -> base("Defend", command)
        is GameCommand.Cast -> base("Cast", command)
            .put("spell", command.spell.value).put("target", command.targetId ?: JSONObject.NULL)
        is GameCommand.UseItem -> base("UseItem", command).put("item", command.item.value)
        is GameCommand.Retreat -> base("Retreat", command)
    }

    private fun base(type: String, command: GameCommand) =
        JSONObject().put("type", type).put("actionId", command.actionId.value)

    private fun request(r: CheckRequest) = JSONObject()
        .put("skill", r.skill.value)
        .put("difficulty", r.difficulty.name)
        .put("challenge", challenge(r.challenge))
        .put("intent", r.intent)
        .put("attribute", r.attribute?.name ?: JSONObject.NULL)
        .put("circumstances", array(r.circumstances) { modifier(it) })
        .put("requirements", array(r.requirements) { req ->
            when (req) {
                is Requirement.HasItem -> JSONObject().put("type", "HasItem").put("item", req.item.value).put("quantity", req.quantity)
                is Requirement.MinimumSkillLevel -> JSONObject().put("type", "MinimumSkillLevel")
                    .put("skill", req.skill.value).put("level", req.level)
            }
        })
        .put("importance", r.importance.name)

    private fun proposal(p: WorldProposal) = JSONObject()
        .put("locations", array(p.locations) { l ->
            JSONObject().put("key", l.key).put("name", l.name).put("kind", l.kind.name).put("description", l.description)
                .put("connectsTo", JSONArray(l.connectsTo)).put("creatures", JSONArray(l.creatures))
        })
        .put("npcs", array(p.npcs) { n ->
            JSONObject().put("key", n.key).put("name", n.name).put("role", n.role)
                .put("description", n.description).put("location", n.location)
        })
        .put("facts", array(p.facts) { f -> JSONObject().put("subject", f.subject).put("text", f.text) })
        .put("startLocation", p.startLocation ?: JSONObject.NULL)

    internal fun challenge(c: ChallengeKey) = JSONObject().put("subject", c.subjectId).put("topic", c.topic)

    internal fun challenge(o: JSONObject) = ChallengeKey(o.getString("subject"), o.getString("topic"))

    internal fun modifier(m: Modifier) = JSONObject().put("source", m.source.name).put("label", m.label).put("value", m.value)

    internal fun modifier(o: JSONObject) =
        Modifier(ModifierSource.valueOf(o.getString("source")), o.getString("label"), o.getInt("value"))
}

/** Key-sorted JSON, independent of the JSONObject implementation's map ordering. */
internal fun canonicalJson(value: Any?): String = when (value) {
    null, JSONObject.NULL -> "null"
    is JSONObject -> value.keys().asSequence().sorted()
        .joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonicalJson(value.get(it)) }
    is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonicalJson(value.get(it)) }
    is String -> JSONObject.quote(value)
    else -> value.toString()
}

private inline fun <T> array(items: Iterable<T>, encode: (T) -> Any): JSONArray =
    JSONArray().apply { items.forEach { put(encode(it)) } }

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

private fun JSONObject.intMap(): Map<String, Int> = keys().asSequence().associateWith { getInt(it) }

private fun JSONObject.nullableInt(key: String): Int? = if (isNull(key)) null else getInt(key)

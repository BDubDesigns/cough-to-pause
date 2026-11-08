package dev.harness.coughtopause.rpg

import org.json.JSONArray
import org.json.JSONObject

/*
 * The AI → world boundary, in four steps:
 *
 *   1. parse     WorldProposalParser.parse(rawJson)         untrusted text  → WorldProposal
 *   2. validate  WorldProposalValidator.validate(...)       WorldProposal   → errors or OK
 *   3. identify  WorldCommitter.commit(...)                 proposal keys   → stable engine ids
 *   4. commit    Rules (CommitWorldProposal / newCampaign)  one atomic save with an event
 *
 * Proposals use their own short keys ("inn", "old_mill") to refer to each other. Keys never
 * become ids: committed entities get engine-assigned ids (loc-0003, npc-0007) from a monotonic
 * counter, so nothing the model writes can collide with, overwrite or rename existing entities.
 * Proposals can only ADD content; existing facts are immutable.
 */

data class WorldProposal(
    val locations: List<ProposedLocation> = emptyList(),
    val npcs: List<ProposedNpc> = emptyList(),
    val facts: List<ProposedFact> = emptyList(),
    /** Required for an initial region, forbidden for later additions. */
    val startLocation: String? = null,
)

data class ProposedLocation(
    val key: String,
    val name: String,
    val kind: LocationKind,
    val description: String,
    /** Proposal keys or existing location ids. */
    val connectsTo: List<String> = emptyList(),
    /** Must be existing catalog creature template ids. */
    val creatures: List<String> = emptyList(),
)

data class ProposedNpc(
    val key: String,
    val name: String,
    val role: String,
    val description: String,
    /** Proposal key or existing location id. */
    val location: String,
)

data class ProposedFact(
    /** Proposal key or existing entity id. */
    val subject: String,
    val text: String,
)

sealed interface ProposalError : DomainError {
    data class Malformed(val detail: String) : ProposalError {
        override val message get() = "Not a valid proposal: $detail"
    }

    data class UnsupportedField(val path: String, val field: String) : ProposalError {
        override val message get() = "$path: field '$field' is not allowed in world proposals"
    }

    data class MissingField(val path: String, val field: String) : ProposalError {
        override val message get() = "$path: missing '$field'"
    }

    data class WrongType(val path: String, val field: String, val expected: String) : ProposalError {
        override val message get() = "$path: '$field' must be $expected"
    }

    data class UnknownKind(val path: String, val value: String) : ProposalError {
        override val message get() = "$path: unsupported location kind '$value'"
    }

    data class InvalidKey(val path: String, val key: String) : ProposalError {
        override val message get() = "$path: key '$key' must match [a-z][a-z0-9_]{0,31}"
    }

    data class DuplicateKey(val key: String) : ProposalError {
        override val message get() = "Key '$key' used more than once"
    }

    data class InvalidText(val path: String, val field: String, val max: Int) : ProposalError {
        override val message get() = "$path: '$field' must be 1..$max characters"
    }

    data class TooMany(val what: String, val count: Int, val max: Int) : ProposalError {
        override val message get() = "$count $what proposed, limit $max"
    }

    data class UnknownReference(val path: String, val reference: String) : ProposalError {
        override val message get() = "$path: '$reference' is neither a proposed key nor an existing entity"
    }

    data class UnknownCreature(val path: String, val creature: String) : ProposalError {
        override val message get() = "$path: creature '$creature' is not in the catalog"
    }

    data object EmptyProposal : ProposalError {
        override val message get() = "Proposal adds nothing"
    }

    data object StartLocationRequired : ProposalError {
        override val message get() = "An initial region must name a proposed startLocation"
    }

    data object StartLocationNotAllowed : ProposalError {
        override val message get() = "startLocation can only be set for an initial region"
    }

    data class MissingRegionFeature(val detail: String) : ProposalError {
        override val message get() = "Initial region needs $detail"
    }

    data class Unreachable(val key: String) : ProposalError {
        override val message get() = "Location '$key' cannot be reached from the start"
    }

    data class SelfConnection(val key: String) : ProposalError {
        override val message get() = "Location '$key' connects to itself"
    }
}

/**
 * Strict parser for model output. Unknown fields are errors, not ignored: a proposal that tries
 * to smuggle in "gold", "xp", "items" or "skills" is refused outright.
 */
object WorldProposalParser {
    private val TOP = setOf("locations", "npcs", "facts", "startLocation")
    private val LOCATION = setOf("key", "name", "kind", "description", "connectsTo", "creatures")
    private val NPC = setOf("key", "name", "role", "description", "location")
    private val FACT = setOf("subject", "text")

    fun parse(raw: String): Validated<WorldProposal, ProposalError> {
        val root = try {
            JSONObject(raw.trim())
        } catch (e: Exception) {
            return Validated.Invalid(listOf(ProposalError.Malformed(e.message ?: "unparseable JSON")))
        }
        val errors = mutableListOf<ProposalError>()
        checkFields(root, "proposal", TOP, errors)
        val locations = objects(root, "locations", "proposal", errors).mapIndexedNotNull { i, o ->
            val path = "locations[$i]"
            checkFields(o, path, LOCATION, errors)
            val kindText = string(o, "kind", path, errors)
            val kind = kindText?.let { text ->
                LocationKind.entries.firstOrNull { it.name.equals(text, ignoreCase = true) }
                    ?: null.also { errors += ProposalError.UnknownKind(path, text) }
            }
            val key = string(o, "key", path, errors)
            val name = string(o, "name", path, errors)
            val description = string(o, "description", path, errors)
            val connects = strings(o, "connectsTo", path, errors)
            val creatures = strings(o, "creatures", path, errors)
            if (key == null || name == null || description == null || kind == null) {
                null
            } else {
                ProposedLocation(key, name, kind, description, connects, creatures)
            }
        }
        val npcs = objects(root, "npcs", "proposal", errors).mapIndexedNotNull { i, o ->
            val path = "npcs[$i]"
            checkFields(o, path, NPC, errors)
            val key = string(o, "key", path, errors)
            val name = string(o, "name", path, errors)
            val role = string(o, "role", path, errors)
            val description = string(o, "description", path, errors)
            val location = string(o, "location", path, errors)
            if (key == null || name == null || role == null || description == null || location == null) {
                null
            } else {
                ProposedNpc(key, name, role, description, location)
            }
        }
        val facts = objects(root, "facts", "proposal", errors).mapIndexedNotNull { i, o ->
            val path = "facts[$i]"
            checkFields(o, path, FACT, errors)
            val subject = string(o, "subject", path, errors)
            val text = string(o, "text", path, errors)
            if (subject == null || text == null) null else ProposedFact(subject, text)
        }
        val start = if (root.has("startLocation") && !root.isNull("startLocation")) {
            string(root, "startLocation", "proposal", errors)
        } else {
            null
        }
        if (errors.isNotEmpty()) return Validated.Invalid(errors)
        return Validated.Valid(WorldProposal(locations, npcs, facts, start))
    }

    private fun checkFields(o: JSONObject, path: String, allowed: Set<String>, errors: MutableList<ProposalError>) {
        o.keys().asSequence().filter { it !in allowed }.sorted().forEach { errors += ProposalError.UnsupportedField(path, it) }
    }

    private fun string(o: JSONObject, field: String, path: String, errors: MutableList<ProposalError>): String? {
        if (!o.has(field) || o.isNull(field)) {
            errors += ProposalError.MissingField(path, field)
            return null
        }
        val v = o.get(field)
        if (v !is String) {
            errors += ProposalError.WrongType(path, field, "a string")
            return null
        }
        return v
    }

    private fun strings(o: JSONObject, field: String, path: String, errors: MutableList<ProposalError>): List<String> {
        if (!o.has(field) || o.isNull(field)) return emptyList()
        val array = o.get(field) as? JSONArray
        if (array == null || (0 until array.length()).any { array.get(it) !is String }) {
            errors += ProposalError.WrongType(path, field, "an array of strings")
            return emptyList()
        }
        return (0 until array.length()).map { array.getString(it) }
    }

    private fun objects(o: JSONObject, field: String, path: String, errors: MutableList<ProposalError>): List<JSONObject> {
        if (!o.has(field) || o.isNull(field)) return emptyList()
        val array = o.get(field) as? JSONArray
        if (array == null || (0 until array.length()).any { array.get(it) !is JSONObject }) {
            errors += ProposalError.WrongType(path, field, "an array of objects")
            return emptyList()
        }
        return (0 until array.length()).map { array.getJSONObject(it) }
    }
}

/** Checks a parsed proposal against the supported entity types, the catalog and the current world. */
class WorldProposalValidator(private val limits: WorldLimits, private val catalog: ContentCatalog) {

    fun validate(proposal: WorldProposal, world: WorldState, initialRegion: Boolean): List<ProposalError> {
        val errors = mutableListOf<ProposalError>()
        if (proposal.locations.isEmpty() && proposal.npcs.isEmpty() && proposal.facts.isEmpty()) {
            return listOf(ProposalError.EmptyProposal)
        }
        if (proposal.locations.size > limits.maxLocationsPerProposal) {
            errors += ProposalError.TooMany("locations", proposal.locations.size, limits.maxLocationsPerProposal)
        }
        if (proposal.npcs.size > limits.maxNpcsPerProposal) {
            errors += ProposalError.TooMany("npcs", proposal.npcs.size, limits.maxNpcsPerProposal)
        }
        if (proposal.facts.size > limits.maxFactsPerProposal) {
            errors += ProposalError.TooMany("facts", proposal.facts.size, limits.maxFactsPerProposal)
        }

        val keys = proposal.locations.map { it.key } + proposal.npcs.map { it.key }
        keys.groupingBy { it }.eachCount().filter { it.value > 1 }.keys.forEach { errors += ProposalError.DuplicateKey(it) }
        val locationKeys = proposal.locations.map { it.key }.toSet()
        val allKeys = keys.toSet()
        fun isLocation(ref: String) = ref in locationKeys || LocationId(ref) in world.locations
        fun isEntity(ref: String) = ref in allKeys || world.containsEntity(ref)

        proposal.locations.forEachIndexed { i, loc ->
            val path = "locations[$i]"
            checkKey(path, loc.key, errors)
            checkText(path, "name", loc.name, limits.maxNameLength, errors)
            checkText(path, "description", loc.description, limits.maxDescriptionLength, errors)
            loc.connectsTo.forEach { ref ->
                if (ref == loc.key) errors += ProposalError.SelfConnection(loc.key)
                else if (!isLocation(ref)) errors += ProposalError.UnknownReference(path, ref)
            }
            if (loc.creatures.size > limits.maxCreaturesPerLocation) {
                errors += ProposalError.TooMany("creatures at ${loc.key}", loc.creatures.size, limits.maxCreaturesPerLocation)
            }
            loc.creatures.forEach { c ->
                if (catalog.creature(CreatureTemplateId(c)) == null) errors += ProposalError.UnknownCreature(path, c)
            }
        }
        proposal.npcs.forEachIndexed { i, npc ->
            val path = "npcs[$i]"
            checkKey(path, npc.key, errors)
            checkText(path, "name", npc.name, limits.maxNameLength, errors)
            checkText(path, "role", npc.role, limits.maxNameLength, errors)
            checkText(path, "description", npc.description, limits.maxDescriptionLength, errors)
            if (!isLocation(npc.location)) errors += ProposalError.UnknownReference(path, npc.location)
        }
        proposal.facts.forEachIndexed { i, fact ->
            val path = "facts[$i]"
            checkText(path, "text", fact.text, limits.maxFactLength, errors)
            if (!isEntity(fact.subject)) errors += ProposalError.UnknownReference(path, fact.subject)
        }

        if (initialRegion) {
            validateInitialRegion(proposal, errors)
        } else if (proposal.startLocation != null) {
            errors += ProposalError.StartLocationNotAllowed
        }
        return errors
    }

    private fun validateInitialRegion(proposal: WorldProposal, errors: MutableList<ProposalError>) {
        val start = proposal.startLocation
        if (start == null || proposal.locations.none { it.key == start }) {
            errors += ProposalError.StartLocationRequired
            return
        }
        val kinds = proposal.locations.map { it.kind }.toSet()
        if (LocationKind.SETTLEMENT !in kinds && LocationKind.INN !in kinds) {
            errors += ProposalError.MissingRegionFeature("a settlement or inn")
        }
        if (LocationKind.WILDERNESS !in kinds) errors += ProposalError.MissingRegionFeature("a wilderness area")
        if (LocationKind.DUNGEON !in kinds) errors += ProposalError.MissingRegionFeature("a dungeon or dangerous site")
        if (proposal.npcs.size < limits.minInitialNpcs) {
            errors += ProposalError.MissingRegionFeature("at least ${limits.minInitialNpcs} NPCs")
        }
        // Every location must be reachable from the start along proposed (undirected) connections.
        val adjacency = mutableMapOf<String, MutableSet<String>>()
        proposal.locations.forEach { loc ->
            loc.connectsTo.forEach { other ->
                adjacency.getOrPut(loc.key) { mutableSetOf() } += other
                adjacency.getOrPut(other) { mutableSetOf() } += loc.key
            }
        }
        val seen = mutableSetOf(start)
        val queue = ArrayDeque(listOf(start))
        while (queue.isNotEmpty()) {
            adjacency[queue.removeFirst()].orEmpty().forEach { if (seen.add(it)) queue.addLast(it) }
        }
        proposal.locations.filter { it.key !in seen }.forEach { errors += ProposalError.Unreachable(it.key) }
    }

    private fun checkKey(path: String, key: String, errors: MutableList<ProposalError>) {
        if (!KEY.matches(key)) errors += ProposalError.InvalidKey(path, key)
    }

    private fun checkText(path: String, field: String, text: String, max: Int, errors: MutableList<ProposalError>) {
        if (text.isBlank() || text.length > max) errors += ProposalError.InvalidText(path, field, max)
    }

    private companion object {
        /** Keys cannot contain '-', so they can never be mistaken for engine ids like loc-0001. */
        val KEY = Regex("[a-z][a-z0-9_]{0,31}")
    }
}

data class CommittedContent(
    val world: WorldState,
    val locations: List<LocationId>,
    val npcs: List<NpcId>,
    val facts: List<FactId>,
    val startLocation: LocationId?,
)

/** Assigns stable ids and merges a *validated* proposal. Deterministic: same input → same ids. */
object WorldCommitter {

    fun commit(world: WorldState, proposal: WorldProposal): CommittedContent {
        var next = world.nextEntityNumber
        val ids = mutableMapOf<String, String>()
        val locationIds = proposal.locations.map { loc ->
            LocationId("loc-%04d".format(next++)).also { ids[loc.key] = it.value }
        }
        val npcIds = proposal.npcs.map { npc ->
            NpcId("npc-%04d".format(next++)).also { ids[npc.key] = it.value }
        }
        fun resolve(ref: String): String = ids[ref] ?: ref

        val locations = world.locations.toMutableMap()
        proposal.locations.forEachIndexed { i, loc ->
            val id = locationIds[i]
            locations[id] = Location(
                id = id,
                name = loc.name.trim(),
                kind = loc.kind,
                description = loc.description.trim(),
                connections = emptySet(),
                creatures = loc.creatures.map { CreatureTemplateId(it) },
            )
        }
        proposal.locations.forEachIndexed { i, loc ->
            val id = locationIds[i]
            loc.connectsTo.map { LocationId(resolve(it)) }.forEach { other ->
                locations[id] = locations.getValue(id).let { it.copy(connections = it.connections + other) }
                locations[other] = locations.getValue(other).let { it.copy(connections = it.connections + id) }
            }
        }
        val npcs = world.npcs.toMutableMap()
        proposal.npcs.forEachIndexed { i, npc ->
            npcs[npcIds[i]] = Npc(npcIds[i], npc.name.trim(), npc.role.trim(), npc.description.trim(), LocationId(resolve(npc.location)))
        }
        val newFacts = proposal.facts.map { fact ->
            WorldFact(FactId("fact-%04d".format(next++)), resolve(fact.subject), fact.text.trim())
        }
        return CommittedContent(
            world = WorldState(locations, npcs, world.facts + newFacts, next),
            locations = locationIds,
            npcs = npcIds,
            facts = newFacts.map { it.id },
            startLocation = proposal.startLocation?.let { LocationId(resolve(it)) },
        )
    }
}

/**
 * Hand-written region used when generation is unavailable or every attempt is rejected, and as
 * the known-good fixture in tests. It goes through the same validator as AI output.
 */
object FallbackRegion {
    val proposal = WorldProposal(
        locations = listOf(
            ProposedLocation(
                "village", "Thornwick", LocationKind.SETTLEMENT,
                "A huddle of slate-roofed cottages around a well, smoke rising from a dozen chimneys.",
                connectsTo = listOf("inn", "woods"),
            ),
            ProposedLocation(
                "inn", "The Crooked Lantern", LocationKind.INN,
                "A low, warm taproom that smells of peat smoke and spilled cider.",
            ),
            ProposedLocation(
                "woods", "Bramblewood", LocationKind.WILDERNESS,
                "Tangled birch and thorn, loud with crows, cut by a single muddy trail.",
                connectsTo = listOf("barrow"),
                creatures = listOf("giant_rat"),
            ),
            ProposedLocation(
                "barrow", "The Old Barrow", LocationKind.DUNGEON,
                "A grassy burial mound whose stone door has recently been forced open.",
                creatures = listOf("goblin"),
            ),
        ),
        npcs = listOf(
            ProposedNpc("innkeeper", "Marta Vell", "innkeeper", "Broad-shouldered, sharp-eyed, misses nothing said at her bar.", "inn"),
            ProposedNpc("woodcutter", "Old Hob", "woodcutter", "Stooped and nervous; he stopped working the far woods a week ago.", "village"),
        ),
        facts = listOf(
            ProposedFact("barrow", "Lights have been seen moving inside the Old Barrow at night."),
            ProposedFact("woodcutter", "Hob's nephew went into Bramblewood five days ago and has not come back."),
        ),
        startLocation = "inn",
    )
}

package dev.harness.coughtopause.adventure

import dev.harness.coughtopause.rpg.Campaign
import dev.harness.coughtopause.rpg.EncounterStatus
import dev.harness.coughtopause.rpg.GameEvent
import dev.harness.coughtopause.rpg.Location
import dev.harness.coughtopause.rpg.Npc
import dev.harness.coughtopause.rpg.OutcomeFact
import dev.harness.coughtopause.rpg.OutcomeFacts

/**
 * The seam where a real dungeon master will plug in.
 *
 * Implementations return `null` when they cannot narrate, and callers fall back and say so. That is
 * deliberate: a scripted description must never be presented as generated prose.
 */
interface NarrativeProvider {
    val id: String

    /** Human-readable provenance, shown in the UI ("Deterministic narrative", "AI: not configured"). */
    val status: String

    fun describeLocation(location: Location, npcs: List<Npc>, exits: List<String>): List<String>?

    fun describeOutcome(campaign: Campaign, events: List<GameEvent>): List<String>?

    /**
     * The whole scene in one call. Providers that need a single request (a model) override this;
     * the default composes the two methods above.
     */
    fun narrateScene(
        campaign: Campaign,
        events: List<GameEvent>,
        preferLocationFirst: Boolean,
        /** False when the room was already described: narrate the result only. */
        includePlace: Boolean = true,
    ): List<String>? {
        val location = campaign.world.locations[campaign.currentLocationId]
        val npcs = campaign.world.npcs.values.filter { it.locationId == campaign.currentLocationId }
        val exits = location?.connections.orEmpty().mapNotNull { campaign.world.locations[it]?.name }
        val outcome = describeOutcome(campaign, events).orEmpty()
        val place = if (includePlace) location?.let { describeLocation(it, npcs, exits) }.orEmpty() else emptyList()
        return (if (preferLocationFirst) place + outcome else outcome + place)
            .filter { it.isNotBlank() }
    }
}

/**
 * The only narrator that ships enabled. Everything it says is derived from committed world facts
 * and resolved events, so it can never contradict the engine.
 */
class DeterministicNarrator : NarrativeProvider {
    override val id = "deterministic"
    override val status = "Andy · scripted narration (no AI dungeon master configured)"

    override fun describeLocation(location: Location, npcs: List<Npc>, exits: List<String>): List<String> {
        val lines = mutableListOf<String>()
        lines += "${location.name}. ${location.description}"
        if (npcs.isNotEmpty()) {
            lines += "Here: " + npcs.joinToString("; ") { "${it.name}, ${it.role}. ${it.description}" }
        }
        if (exits.isNotEmpty()) {
            lines += "You can go to ${exits.joinToString(", ")}."
        } else {
            lines += "There is nowhere obvious to go from here."
        }
        return lines
    }

    override fun describeOutcome(campaign: Campaign, events: List<GameEvent>): List<String> =
        OutcomeFacts.from(campaign, events).mapNotNull(SpokenOutcome::line)
}

/**
 * How Andy says an [OutcomeFact] aloud. Wording only: every number and name comes from the fact,
 * which the engine produced, so this cannot disagree with the rules.
 *
 * Units are spelled out ("hit points", "mana points"): TTS reads "HP"/"MP" as letters.
 */
object SpokenOutcome {

    /** One spoken line, or null when the fact is better left unsaid (withheld XP, a hit already told). */
    fun line(fact: OutcomeFact): String? = when (fact) {
        is OutcomeFact.CheckRolled -> {
            val dice = if (fact.natural != null) {
                "You roll ${fact.natural}, total ${fact.total} against ${fact.dc}"
            } else {
                "No die is needed"
            }
            "$dice: ${fact.outcome.name.lowercase().replace('_', ' ')}. (${fact.skill})"
        }
        is OutcomeFact.AttackRolled ->
            "You attack ${the(fact.target)}: ${fact.natural}, total ${fact.total} against defense ${fact.defense}, " +
                if (fact.hit) "a hit." else "a miss."
        is OutcomeFact.EnemyHurt -> {
            val source = if (fact.bySpell) "Your spell" else "Your blow"
            "$source strikes ${the(fact.target)} for ${fact.amount}; " +
                if (fact.felled) "it falls." else "it has ${fact.hpAfter} hit points left."
        }
        // A hit is told by the PlayerHurt line that follows; only a miss needs its own line.
        is OutcomeFact.EnemyAttacked -> if (fact.hit) null else "${the(fact.attacker).cap()} attacks and misses."
        is OutcomeFact.PlayerHurt ->
            "${the(fact.attacker).cap()} hits you for ${fact.amount}; ${fact.hpAfter} hit points left."
        is OutcomeFact.Guarded -> "You raise your guard (+${fact.bonus} defense until your next action)."
        is OutcomeFact.SpellCast -> "You cast ${fact.spell.lowercase()}; ${fact.mpAfter} mana points left."
        is OutcomeFact.CreatureIdentified -> {
            val entry = "level ${fact.level} ${fact.threat.name.lowercase()}, ${fact.maxHp} hit points, " +
                if (fact.possibleDrops.isEmpty()) {
                    "carries nothing worth taking."
                } else {
                    "may carry " + orList(fact.possibleDrops.map { "${it.item.lowercase()} (${it.chancePercent} percent)" }) + "."
                }
            if (fact.learned) {
                "You study ${the(fact.creature)}: $entry ${fact.mpAfter} mana points left."
            } else {
                "${fact.creature}, from what you already know: $entry"
            }
        }
        is OutcomeFact.ItemUsed -> "You use the ${fact.item.lowercase()}: ${fact.hpAfter} hit points now."
        is OutcomeFact.FightStarted ->
            "Something hostile is here: " +
                fact.enemies.joinToString { "${it.name.lowercase()} (${it.hp} hit points)" } +
                ". Attack, defend, cast, or retreat."
        is OutcomeFact.FightEnded -> {
            val ending = when (fact.status) {
                EncounterStatus.VICTORY -> "The fight is over, and you are standing."
                EncounterStatus.RETREATED -> "You get out alive."
                EncounterStatus.DEFEAT -> "You fall."
                EncounterStatus.ACTIVE -> "The fight goes on."
            }
            val found = buildList {
                if (fact.gold > 0) add("${fact.gold} gold")
                fact.loot.forEach { (name, count) -> add(if (count > 1) "$count ${name.lowercase()}s" else name.lowercase()) }
            }
            if (found.isEmpty()) ending else "$ending You find ${andList(found)}."
        }
        is OutcomeFact.Recovered ->
            "You wake at ${fact.location} with ${fact.hp} hit points, ${fact.goldLost} gold lighter. The save is intact."
        is OutcomeFact.XpGained ->
            if (fact.levelAfter == fact.levelBefore) {
                "${fact.skill} improves: +${fact.amount} XP."
            } else {
                "${fact.skill} improves: +${fact.amount} XP, level ${fact.levelBefore} to ${fact.levelAfter}."
            }
        is OutcomeFact.XpWithheld -> null
        is OutcomeFact.Travelled -> "You make your way to ${fact.destination}."
        is OutcomeFact.GoldSpent -> "You spend ${fact.amount} gold on ${fact.purpose}."
        is OutcomeFact.FactLearned -> fact.text
    }

    private fun the(name: String) = "the ${name.lowercase()}"

    private fun String.cap() = replaceFirstChar { it.uppercase() }

    private fun andList(items: List<String>) = joinWith(items, "and")

    private fun orList(items: List<String>) = joinWith(items, "or")

    private fun joinWith(items: List<String>, last: String) = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> items.dropLast(1).joinToString(", ") + " $last " + items.last()
    }
}

/**
 * Turns committed events plus the current location into the exact lines the TTS engine speaks.
 * One line at a time is what makes the cough checkpoint meaningful: pausing restarts the line that
 * was interrupted, not the whole scene.
 */
object NarrationScriptBuilder {

    const val MAX_LINES = 6

    fun sceneId(campaign: Campaign, events: List<GameEvent>): String =
        "rpg-${campaign.id.value}-${events.size}"

    fun build(
        campaign: Campaign,
        events: List<GameEvent>,
        narrator: NarrativeProvider,
        preferLocationFirst: Boolean,
        /** Extra spoken lines that are not events, e.g. a fact an NPC has just confirmed. */
        extraLines: List<String> = emptyList(),
        /** False when the room was already described: narrate only what changed. */
        includePlace: Boolean = true,
    ): List<dev.harness.coughtopause.core.ScriptLine> {
        val texts = (narrator.narrateScene(campaign, events, preferLocationFirst, includePlace).orEmpty() + extraLines)
            .filter { it.isNotBlank() }
            .take(MAX_LINES)

        return texts.mapIndexed { index, text ->
            dev.harness.coughtopause.core.ScriptLine(id = "R%02d".format(index + 1), index = index, text = text)
        }
    }
}

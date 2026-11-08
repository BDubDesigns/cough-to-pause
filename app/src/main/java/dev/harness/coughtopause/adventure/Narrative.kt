package dev.harness.coughtopause.adventure

import dev.harness.coughtopause.rpg.Campaign
import dev.harness.coughtopause.rpg.EventPayload
import dev.harness.coughtopause.rpg.GameEvent
import dev.harness.coughtopause.rpg.Location
import dev.harness.coughtopause.rpg.Npc

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
    ): List<String>? {
        val location = campaign.world.locations[campaign.currentLocationId]
        val npcs = campaign.world.npcs.values.filter { it.locationId == campaign.currentLocationId }
        val exits = location?.connections.orEmpty().mapNotNull { campaign.world.locations[it]?.name }
        val outcome = describeOutcome(campaign, events).orEmpty()
        val place = location?.let { describeLocation(it, npcs, exits) }.orEmpty()
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

    override fun describeOutcome(campaign: Campaign, events: List<GameEvent>): List<String> {
        val lines = mutableListOf<String>()
        for (event in events) {
            when (val payload = event.payload) {
                is EventPayload.CheckResolved -> {
                    val roll = payload.roll
                    val dice = if (roll.natural != null) {
                        "You roll ${roll.natural}, total ${roll.total} against ${roll.difficultyClass}"
                    } else {
                        "No die is needed"
                    }
                    lines += "$dice: ${roll.outcome.name.lowercase().replace('_', ' ')}. (${roll.skill.value})"
                }
                is EventPayload.SkillXp ->
                    lines += "${payload.skill.value.replace('_', ' ')} improves: +${payload.amount} XP, level ${payload.levelBefore} to ${payload.levelAfter}."
                is EventPayload.Travelled -> {
                    val name = campaign.world.locations[payload.to]?.name ?: payload.to.value
                    lines += "You make your way to $name."
                }
                is EventPayload.GoldSpent ->
                    lines += "You spend ${payload.amount} gold on ${payload.purpose}."
                is EventPayload.WorldContentAccepted -> payload.facts.forEach { factId ->
                    campaign.world.facts.firstOrNull { it.id == factId }?.let { lines += it.text }
                }
                is EventPayload.EncounterStarted ->
                    lines += "Something hostile is here (combat is not part of this slice)."
                else -> Unit
            }
        }
        return lines
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
    ): List<dev.harness.coughtopause.core.ScriptLine> {
        val texts = narrator.narrateScene(campaign, events, preferLocationFirst)
            .orEmpty()
            .filter { it.isNotBlank() }
            .take(MAX_LINES)

        return texts.mapIndexed { index, text ->
            dev.harness.coughtopause.core.ScriptLine(id = "R%02d".format(index + 1), index = index, text = text)
        }
    }
}

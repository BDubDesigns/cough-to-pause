package dev.harness.coughtopause.adventure

import dev.harness.coughtopause.rpg.ContentCatalog
import dev.harness.coughtopause.rpg.DifficultyTier
import dev.harness.coughtopause.rpg.ItemTemplateId
import dev.harness.coughtopause.rpg.LocationId
import dev.harness.coughtopause.rpg.NpcId
import dev.harness.coughtopause.rpg.SkillCatalog
import dev.harness.coughtopause.rpg.SkillId
import dev.harness.coughtopause.rpg.SpellCatalog
import dev.harness.coughtopause.rpg.SpellId

/** What the player asked for, before any rules are involved. */
sealed interface AdventureIntent {
    data object Look : AdventureIntent
    data class Travel(val destination: LocationId, val name: String) : AdventureIntent
    data class Talk(val npc: NpcId, val name: String) : AdventureIntent

    /** "ask Marta about the barrow". [topicKey] is null when the player did not name a topic. */
    data class Ask(val npc: NpcId, val name: String, val topicKey: String?) : AdventureIntent
    data class Check(
        val skill: SkillId,
        val difficulty: DifficultyTier,
        val subjectId: String,
        val topic: String,
        val label: String,
    ) : AdventureIntent

    data object Help : AdventureIntent

    /** Start or continue a fight with whatever lives here. */
    data object Attack : AdventureIntent

    data object Defend : AdventureIntent

    /** Cast a catalogue spell; [spell] is null when the player did not name one. */
    data class Cast(val spell: SpellId?) : AdventureIntent

    /** Use a carried consumable; [item] is null when the player did not name one. */
    data class Use(val item: ItemTemplateId?) : AdventureIntent

    data object Retreat : AdventureIntent

    /**
     * "Andy, repeat" / "Play" after Andy finished: speak the last prompt again. Carries no ids on
     * purpose — a repeat must never turn into a second travel or a second check.
     */
    data object Repeat : AdventureIntent

    data class Unknown(val text: String) : AdventureIntent
}

/** The slice of the world the interpreter needs; keeps it a pure function of text + world. */
data class WorldView(
    val locationId: String,
    val locations: List<Pair<String, String>>,
    val npcs: List<NpcView>,
)

data class NpcView(
    val id: String,
    val name: String,
    /** key to label, so the interpreter can recognise "ask about the barrow". */
    val topics: List<Pair<String, String>> = emptyList(),
)

/**
 * Maps a natural-language action onto one of the commands the engine actually accepts.
 *
 * Deliberately small and deterministic: it never invents a difficulty, only a tier, and the
 * engine still validates bounds, prerequisites and whether the subject is legitimately challengeable.
 * A model-backed interpreter can replace this later without touching the rules.
 */
class IntentInterpreter {

    fun interpret(rawInput: String, world: WorldView): AdventureIntent {
        val text = normalise(rawInput)
        if (text.isEmpty()) return AdventureIntent.Unknown(rawInput)
        // Exact-ish match only: "persuade Mira to help" is an action, not a help request.
        if (text == "help" || text == "commands" || text.startsWith("what can i do")) {
            return AdventureIntent.Help
        }
        // Exact matches only: "play" is a repeat; "play the lute" is not a command this slice knows.
        if (REPEAT_EXACT.any { text == it }) return AdventureIntent.Repeat
        // Combat verbs, checked before skills so "attack the goblin" is not a check.
        if (FIGHT.any { text == it || text.startsWith("$it ") }) {
            return when {
                text.startsWith("identify") -> AdventureIntent.Cast(SpellCatalog.IDENTIFY)
                text.startsWith("cast") -> AdventureIntent.Cast(
                    when {
                        text.contains("identify") -> SpellCatalog.IDENTIFY
                        text.contains("ember") || text.contains("bolt") -> SpellCatalog.EMBER_BOLT
                        else -> null
                    },
                )
                text.startsWith("use") || text.startsWith("drink") -> AdventureIntent.Use(
                    if (text.contains("draught") || text.contains("potion")) ContentCatalog.HEALING_DRAUGHT else null,
                )
                text.startsWith("defend") || text.startsWith("block") || text.startsWith("guard") -> AdventureIntent.Defend
                text.startsWith("retreat") || text.startsWith("flee") || text.startsWith("run away") -> AdventureIntent.Retreat
                else -> AdventureIntent.Attack
            }
        }
        if (LOOK.any { text == it || text.startsWith("$it ") }) return AdventureIntent.Look

        // "ask <name> about <topic>" — checked before plain talk so the topic is not lost.
        if (text.startsWith("ask") || text.contains(" ask ")) {
            val npc = findNpc(text, world)
            if (npc != null) {
                val topic = npc.topics.firstOrNull { (key, label) ->
                    text.contains(key) || text.contains(label.lowercase())
                }
                return AdventureIntent.Ask(NpcId(npc.id), npc.name, topic?.first)
            }
        }

        findNpc(text, world)?.let { npc ->
            if (TALK.any { text.contains(it) }) return AdventureIntent.Talk(NpcId(npc.id), npc.name)
        }
        findEntity(text, world.locations)?.let { (id, name) ->
            if (text.contains("go") || text.contains("travel") || text.contains("walk") ||
                text.contains("enter") || text.contains("head")
            ) {
                return AdventureIntent.Travel(LocationId(id), name)
            }
        }
        if (TALK.any { text.contains(it) } && world.npcs.size == 1) {
            // "talk to someone" with no name: pick the only NPC here.
            val only = world.npcs.first()
            return AdventureIntent.Talk(NpcId(only.id), only.name)
        }

        SKILL_WORDS.firstOrNull { (word, _) -> text.contains(word) }?.let { (word, skill) ->
            val subject = findNpc(text, world)?.id
                ?: findEntity(text, world.locations)?.first
                ?: world.locationId
            return AdventureIntent.Check(
                skill = skill,
                difficulty = DifficultyTier.MODERATE,
                subjectId = subject,
                topic = "action-${word.take(20)}",
                label = rawInput.trim().take(60),
            )
        }
        return AdventureIntent.Unknown(rawInput)
    }

    private fun findEntity(text: String, candidates: List<Pair<String, String>>): Pair<String, String>? =
        candidates.firstOrNull { (_, name) -> name.isNotBlank() && text.contains(normalise(name)) }

    private fun findNpc(text: String, world: WorldView): NpcView? =
        world.npcs.firstOrNull { it.name.isNotBlank() && text.contains(normalise(it.name)) }
            // A near-miss the player would call a typo: "mara" for "marta". One edit, long words only.
            ?: world.npcs.firstOrNull { npc ->
                npc.name.isNotBlank() && words(text).any { word -> closeTo(word, normalise(npc.name)) }
            }
            ?: world.npcs.singleOrNull()

    private fun words(text: String): List<String> = text.split(' ').filter { it.length >= 4 }

    private fun closeTo(word: String, name: String): Boolean =
        editDistanceAtMostOne(word, name.substringBefore(' ')) || editDistanceAtMostOne(word, name)

    /** True when [a] and [b] differ by at most one insert, delete or substitution. */
    private fun editDistanceAtMostOne(a: String, b: String): Boolean {
        if (a == b) return true
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        var i = 0
        var j = 0
        var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) {
                i++
                j++
                continue
            }
            if (++edits > 1) return false
            when {
                a.length > b.length -> i++
                b.length > a.length -> j++
                else -> {
                    i++
                    j++
                }
            }
        }
        return edits + (a.length - i) + (b.length - j) <= 1
    }

    private fun normalise(input: String): String = input.lowercase()
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private companion object {
        val LOOK = listOf("look", "look around", "where am i", "describe", "survey")
        val TALK = listOf("talk", "speak", "ask", "greet", "chat")

        /** After narration completes, these ask Andy to say it again rather than to act. */
        /** Openers that mean combat, so they win over the skill keywords below. */
        val FIGHT = listOf(
            "attack", "fight", "strike", "hit", "defend", "block", "guard", "cast", "identify", "use", "drink",
            "retreat", "flee", "run away",
        )

        val REPEAT_EXACT = listOf(
            "repeat",
            "repeat that",
            "repeat please",
            "again",
            "say that again",
            "say it again",
            "say again",
            "replay",
            "play",
            "resume",
            "what did you say",
            "what was that",
        )

        /** Ordered: the first keyword found wins. */
        val SKILL_WORDS: List<Pair<String, SkillId>> = listOf(
            "pick lock" to SkillCatalog.LOCKPICKING,
            "pick the lock" to SkillCatalog.LOCKPICKING,
            "climb" to SkillCatalog.ATHLETICS,
            "jump" to SkillCatalog.ATHLETICS,
            "sneak" to SkillCatalog.STEALTH,
            "hide" to SkillCatalog.STEALTH,
            "search" to SkillCatalog.PERCEPTION,
            "listen" to SkillCatalog.PERCEPTION,
            "watch" to SkillCatalog.PERCEPTION,
            "read" to SkillCatalog.LORE,
            "recall" to SkillCatalog.LORE,
            "remember" to SkillCatalog.LORE,
            "persuade" to SkillCatalog.PERSUASION,
            "convince" to SkillCatalog.PERSUASION,
            "lie" to SkillCatalog.DECEPTION,
            "bluff" to SkillCatalog.DECEPTION,
            "threaten" to SkillCatalog.INTIMIDATION,
            "intimidate" to SkillCatalog.INTIMIDATION,
            "forage" to SkillCatalog.SURVIVAL,
            "track" to SkillCatalog.SURVIVAL,
            "herbs" to SkillCatalog.HERBALISM,
            "heal" to SkillCatalog.HERBALISM,
            "mine" to SkillCatalog.MINING,
            "forge" to SkillCatalog.SMITHING,
            "cast" to SkillCatalog.MAGIC,
            "study" to SkillCatalog.INSIGHT,
        )
    }
}

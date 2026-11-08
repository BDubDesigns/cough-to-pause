package dev.harness.coughtopause.adventure

import dev.harness.coughtopause.rpg.DifficultyTier
import dev.harness.coughtopause.rpg.LocationId
import dev.harness.coughtopause.rpg.NpcId
import dev.harness.coughtopause.rpg.SkillCatalog
import dev.harness.coughtopause.rpg.SkillId

/** What the player asked for, before any rules are involved. */
sealed interface AdventureIntent {
    data object Look : AdventureIntent
    data class Travel(val destination: LocationId, val name: String) : AdventureIntent
    data class Talk(val npc: NpcId, val name: String) : AdventureIntent
    data class Check(
        val skill: SkillId,
        val difficulty: DifficultyTier,
        val subjectId: String,
        val topic: String,
        val label: String,
    ) : AdventureIntent

    data object Help : AdventureIntent
    data class Unknown(val text: String) : AdventureIntent
}

/** The slice of the world the interpreter needs; keeps it a pure function of text + world. */
data class WorldView(
    val locationId: String,
    val locations: List<Pair<String, String>>,
    val npcs: List<Pair<String, String>>,
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
        if (LOOK.any { text == it || text.startsWith("$it ") }) return AdventureIntent.Look

        findEntity(text, world.npcs)?.let { (id, name) ->
            if (TALK.any { text.contains(it) }) return AdventureIntent.Talk(NpcId(id), name)
        }
        findEntity(text, world.locations)?.let { (id, name) ->
            if (text.contains("go") || text.contains("travel") || text.contains("walk") ||
                text.contains("enter") || text.contains("head")
            ) {
                return AdventureIntent.Travel(LocationId(id), name)
            }
        }
        if (TALK.any { text.contains(it) } && world.npcs.isNotEmpty()) {
            // "talk to someone" with no name: pick the only NPC here, if there is exactly one.
            if (world.npcs.size == 1) {
                val (id, name) = world.npcs.first()
                return AdventureIntent.Talk(NpcId(id), name)
            }
        }

        SKILL_WORDS.firstOrNull { (word, _) -> text.contains(word) }?.let { (word, skill) ->
            val subject = findEntity(text, world.npcs)?.first
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

    private fun normalise(input: String): String = input.lowercase()
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private companion object {
        val LOOK = listOf("look", "look around", "where am i", "describe", "survey")
        val TALK = listOf("talk", "speak", "ask", "greet", "chat")

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

package dev.harness.coughtopause.adventure

import dev.harness.coughtopause.rpg.Npc
import dev.harness.coughtopause.rpg.SkillCatalog
import dev.harness.coughtopause.rpg.SkillId

/**
 * What an NPC is willing to talk about, and the fact they reveal when the check succeeds.
 *
 * Authored data, not generated: the fact text is committed to the world only after the engine
 * resolves a check, so a conversation can never invent world truth on its own.
 */
data class NpcTopic(
    val key: String,
    val label: String,
    val skill: SkillId,
    val fact: String,
)

object NpcTopics {

    private val byName: Map<String, List<NpcTopic>> = mapOf(
        "marta vell" to listOf(
            NpcTopic(
                key = "barrow",
                label = "the barrow",
                skill = SkillCatalog.LORE,
                fact = "Lights have been seen moving inside the Old Barrow at night.",
            ),
            NpcTopic(
                key = "road",
                label = "the coast road",
                skill = SkillCatalog.PERSUASION,
                fact = "The coast road north has been empty since the fog came in.",
            ),
            NpcTopic(
                key = "herself",
                label = "herself",
                skill = SkillCatalog.PERSUASION,
                fact = "Marta has kept the inn since her brother vanished on the coast road.",
            ),
        ),
        "old hob" to listOf(
            NpcTopic(
                key = "nephew",
                label = "his nephew",
                skill = SkillCatalog.PERSUASION,
                fact = "Hob's nephew went into Bramblewood five days ago and has not come back.",
            ),
            NpcTopic(
                key = "woods",
                label = "the woods",
                skill = SkillCatalog.LORE,
                fact = "Something in Bramblewood has been taking the birds.",
            ),
        ),
    )

    private val byRole: Map<String, List<NpcTopic>> = mapOf(
        "innkeeper" to byName.getValue("marta vell"),
        "woodcutter" to byName.getValue("old hob"),
    )

    fun forNpc(npc: Npc): List<NpcTopic> =
        byName[npc.name.lowercase()] ?: byRole[npc.role.lowercase()] ?: emptyList()

    /** Matches a topic by its key or label inside the player's sentence. */
    fun find(npc: Npc, text: String): NpcTopic? {
        val lower = text.lowercase()
        return forNpc(npc).firstOrNull { topic ->
            lower.contains(topic.key) || lower.contains(topic.label.lowercase())
        }
    }
}

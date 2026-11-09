package dev.harness.coughtopause.adventure

import dev.harness.coughtopause.rpg.FallbackRegion
import dev.harness.coughtopause.rpg.Npc
import dev.harness.coughtopause.rpg.NpcId
import dev.harness.coughtopause.rpg.LocationId
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Talking must describe the person, not re-read the room, and every seeded NPC must announce what
 * they can be asked about — otherwise the topic system is invisible to the player.
 */
class NpcTopicsTest {

    private fun npc(name: String, role: String) =
        Npc(NpcId(name.lowercase().replace(' ', '-')), name, role, "a description", LocationId("inn"))

    @Test
    fun talkingDescribesThePersonAndTheirTopics() {
        val marta = npc("Marta Vell", "innkeeper")
        val lines = NpcTopics.lines(marta)

        assertTrue("the first line is about them", lines.first().startsWith("Marta Vell, innkeeper"))
        assertTrue("the room must not be re-described", lines.none { it.contains("Crooked Lantern") })
        assertTrue(
            "the player is told what can be asked about",
            lines.last().contains("the barrow") && lines.last().contains("herself"),
        )
    }

    @Test
    fun everySeededSpeakerHasSomethingToTalkAbout() {
        // The fallback region is the only content that ships, so its NPCs must not be silent.
        val seeded = FallbackRegion.proposal.npcs.map { npc(it.name, it.role) }
        assertTrue("the fallback region has NPCs", seeded.isNotEmpty())
        seeded.forEach { person ->
            assertTrue(
                "${person.name} needs at least one topic or talking to them is pointless",
                NpcTopics.forNpc(person).isNotEmpty(),
            )
            assertTrue("and the prompt must name one", NpcTopics.lines(person).size > 1)
        }
    }
}

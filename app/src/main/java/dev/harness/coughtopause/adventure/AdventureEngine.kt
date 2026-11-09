package dev.harness.coughtopause.adventure

import dev.harness.coughtopause.rpg.ActionId
import dev.harness.coughtopause.rpg.ActionProcessor
import dev.harness.coughtopause.rpg.Attribute
import dev.harness.coughtopause.rpg.AttributeRoller
import dev.harness.coughtopause.rpg.BackgroundId
import dev.harness.coughtopause.rpg.Backgrounds
import dev.harness.coughtopause.rpg.Campaign
import dev.harness.coughtopause.rpg.CampaignId
import dev.harness.coughtopause.rpg.CharacterFactory
import dev.harness.coughtopause.rpg.CreateCampaignResult
import dev.harness.coughtopause.rpg.CreationError
import dev.harness.coughtopause.rpg.FileCampaignStore
import dev.harness.coughtopause.rpg.FallbackRegion
import dev.harness.coughtopause.rpg.GameCommand
import dev.harness.coughtopause.rpg.ItemTemplateId
import dev.harness.coughtopause.rpg.LoadResult
import dev.harness.coughtopause.rpg.PlayerCharacter
import dev.harness.coughtopause.rpg.RolledAttributeSet
import dev.harness.coughtopause.rpg.SkillCatalog
import dev.harness.coughtopause.rpg.SplitMix64
import dev.harness.coughtopause.rpg.Submission
import dev.harness.coughtopause.rpg.Validated
import java.io.File

/** One resolved roll, as the UI shows it. */
data class RollView(
    val skill: String,
    val natural: Int?,
    val total: Int?,
    val dc: Int?,
    val outcome: String,
    val xpGained: Int,
)

/** Everything the adventure screen needs, derived from the authoritative campaign. */
data class AdventureSnapshot(
    val campaignId: String,
    val characterName: String,
    val background: String,
    val day: Int,
    val locationName: String,
    val locationDescription: String,
    val npcs: List<String>,
    val exits: List<String>,
    val hp: Int,
    val maxHp: Int,
    val mp: Int,
    val maxMp: Int,
    val gold: Int,
    val attributes: List<Pair<String, Int>>,
    val skills: List<Pair<String, Int>>,
    /** Every roll in the campaign, newest first. The audit trail the review asked for. */
    val rolls: List<RollView>,
    val lastRoll: RollView?,
    /** The fight in progress (or the one that just ended), for the combat card. */
    val encounter: EncounterView? = null,
    val transcript: List<String>,
    val narrationSource: String,
)

data class EncounterView(
    val enemies: List<EnemyView>,
    val round: Int,
    val status: String,
    val playerGuard: Int,
    val identified: List<String>,
)

data class EnemyView(val name: String, val hp: Int, val maxHp: Int)

/**
 * Thin adapter over the authoritative `:rpg` engine.
 *
 * It owns no game state of its own: every read comes from [Campaign], every write goes through
 * [ActionProcessor], which validates, applies and atomically persists. No Android types here, so
 * the whole vertical slice is unit-testable on the JVM.
 */
class AdventureEngine(
    rootDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val directory = rootDir
    private val store = FileCampaignStore(rootDir)
    private val processor = ActionProcessor(store, clock = clock)
    private val factory = CharacterFactory()

    fun rollAttributes(seed: Long): RolledAttributeSet = AttributeRoller().roll(SplitMix64(seed))

    /**
     * Best rolled score to the attribute this background leans on, down to the worst.
     *
     * The engine's assignment map is attribute → *index into the rolled list*, not the score
     * itself, so the indices are ranked by score and handed out in priority order.
     */
    fun autoAssign(rolled: RolledAttributeSet, backgroundId: BackgroundId): Map<Attribute, Int> {
        val order = priorityFor(backgroundId)
        val rankedIndices = rolled.scores.indices.sortedByDescending { rolled.scores[it] }
        return order.mapIndexed { position, attribute -> attribute to rankedIndices[position] }.toMap()
    }

    fun createCharacter(
        name: String,
        rolled: RolledAttributeSet,
        assignment: Map<Attribute, Int>,
        backgroundId: BackgroundId,
    ): Validated<PlayerCharacter, CreationError> = factory.create(name, rolled, assignment, backgroundId)

    /** The starting region is the validated fallback; AI-proposed regions are a later slice. */
    fun createCampaign(id: CampaignId, seed: Long, character: PlayerCharacter): CreateCampaignResult =
        processor.createCampaign(id, seed, character, FallbackRegion.proposal)

    fun load(id: CampaignId): Campaign? = (processor.load(id) as? LoadResult.Loaded)?.campaign

    fun listCampaigns(): List<CampaignId> = store.list()

    /** Deletes every saved campaign. Returns how many files were removed. */
    fun deleteAll(): Int {
        val files = directory.listFiles { file -> file.isFile && file.name.endsWith(".json") }
            ?: return 0
        return files.count { it.delete() }
    }

    fun submit(id: CampaignId, command: GameCommand): Submission = processor.submit(id, command)

    fun newActionId(): ActionId = ActionId("a-${clock()}-${(0..9999).random()}")

    /** True when this carried item can be used up to restore something. */
    fun carriedHeals(item: ItemTemplateId): Boolean =
        (dev.harness.coughtopause.rpg.ContentCatalog.STARTER.item(item)?.healsHp ?: 0) > 0

    fun snapshot(campaign: Campaign, transcript: List<String>, narrationSource: String): AdventureSnapshot {
        val location = campaign.world.locations[campaign.currentLocationId]
        val npcsHere = campaign.world.npcs.values
            .filter { it.locationId == campaign.currentLocationId }
            .map { "${it.name} — ${it.role}" }
        val exits = location?.connections.orEmpty().mapNotNull { id ->
            campaign.world.locations[id]?.name
        }
        val allRolls = campaign.rolls.map { roll ->
            val xp = campaign.events
                .filter { it.actionId == roll.actionId }
                .mapNotNull { (it.payload as? dev.harness.coughtopause.rpg.EventPayload.SkillXp) }
                .sumOf { it.amount }
            RollView(
                skill = roll.skill.value,
                natural = roll.natural,
                total = roll.total,
                dc = roll.difficultyClass,
                outcome = roll.outcome.name.lowercase().replace('_', ' '),
                xpGained = xp,
            )
        }.reversed()
        val lastRoll = allRolls.firstOrNull()
        return AdventureSnapshot(
            campaignId = campaign.id.value,
            characterName = campaign.player.name,
            background = campaign.player.background.value.replace('_', ' '),
            day = campaign.day,
            locationName = location?.name ?: "unknown",
            locationDescription = location?.description.orEmpty(),
            npcs = npcsHere,
            exits = exits,
            hp = campaign.player.vitals.hp,
            maxHp = campaign.player.vitals.maxHp,
            mp = campaign.player.vitals.mp,
            maxMp = campaign.player.vitals.maxMp,
            gold = campaign.player.gold,
            attributes = Attribute.entries.map {
                it.abbreviation to campaign.player.attributes[it]
            },
            skills = listOf(
                SkillCatalog.PERSUASION,
                SkillCatalog.PERCEPTION,
                SkillCatalog.ATHLETICS,
                SkillCatalog.STEALTH,
                SkillCatalog.LORE,
            ).map { skill ->
                skill.value to (campaign.player.skillXp[skill] ?: 0)
            },
            rolls = allRolls,
            lastRoll = lastRoll,
            encounter = campaign.encounter?.let { enc ->
                EncounterView(
                    enemies = enc.enemies.map { enemy ->
                        EnemyView(
                            name = dev.harness.coughtopause.rpg.ContentCatalog.STARTER
                                .creature(enemy.template)?.name ?: enemy.template.value,
                            hp = enemy.hp,
                            maxHp = enemy.maxHp,
                        )
                    },
                    round = enc.round,
                    status = enc.status.name.lowercase(),
                    playerGuard = enc.playerGuard,
                    identified = campaign.identified.map { it.value },
                )
            },
            transcript = transcript.takeLast(40),
            narrationSource = narrationSource,
        )
    }

    private fun priorityFor(backgroundId: BackgroundId): List<Attribute> = when (backgroundId) {
        Backgrounds.HEDGE_APPRENTICE ->
            listOf(Attribute.INTELLIGENCE, Attribute.WISDOM, Attribute.DEXTERITY, Attribute.CONSTITUTION, Attribute.CHARISMA, Attribute.STRENGTH)
        Backgrounds.STREET_RUNNER ->
            listOf(Attribute.DEXTERITY, Attribute.CHARISMA, Attribute.CONSTITUTION, Attribute.INTELLIGENCE, Attribute.WISDOM, Attribute.STRENGTH)
        Backgrounds.WANDERING_MINSTREL ->
            listOf(Attribute.CHARISMA, Attribute.DEXTERITY, Attribute.INTELLIGENCE, Attribute.WISDOM, Attribute.CONSTITUTION, Attribute.STRENGTH)
        else ->
            listOf(Attribute.STRENGTH, Attribute.CONSTITUTION, Attribute.DEXTERITY, Attribute.WISDOM, Attribute.CHARISMA, Attribute.INTELLIGENCE)
    }

    companion object {
        val BACKGROUNDS: List<Pair<String, String>> = Backgrounds.all.map { it.id.value to it.name }
    }
}

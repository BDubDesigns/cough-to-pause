package dev.harness.coughtopause.rpg

/** One attribute roll: four d6, the lowest dropped. */
data class AttributeRoll(val dice: List<Int>) {
    init {
        require(dice.size == 4 && dice.all { it in 1..6 }) { "4d6 expected, got $dice" }
    }

    val score: Int get() = dice.sum() - dice.min()
}

/** Six rolled scores in roll order, plus every set the safeguard threw away (for transparency). */
data class RolledAttributeSet(
    val rolls: List<AttributeRoll>,
    val rerolledSets: List<List<AttributeRoll>>,
    /** True when every attempt was poor and the best rejected set was kept. */
    val safeguardExhausted: Boolean,
) {
    init {
        require(rolls.size == Attribute.entries.size) { "six rolls expected" }
    }

    val scores: List<Int> get() = rolls.map { it.score }
}

class AttributeRoller(private val safeguard: AttributeSafeguard = AttributeSafeguard()) {

    fun roll(rng: RandomSource): RolledAttributeSet {
        val rejected = mutableListOf<List<AttributeRoll>>()
        while (true) {
            val set = List(Attribute.entries.size) { AttributeRoll(List(4) { Dice.roll(rng, 6) }) }
            if (safeguard.accepts(set.map { it.score })) return RolledAttributeSet(set, rejected.toList(), false)
            rejected += set
            if (rejected.size > safeguard.maxRerolls) {
                val best = rejected.maxBy { s -> s.sumOf { it.score } }
                return RolledAttributeSet(best, rejected.filter { it !== best }, true)
            }
        }
    }
}

sealed interface CreationError : DomainError {
    data class MissingAttribute(val attribute: Attribute) : CreationError {
        override val message get() = "${attribute.abbreviation} has no score assigned"
    }

    data class ScoreUsedTwice(val index: Int, val attributes: List<Attribute>) : CreationError {
        override val message get() = "Rolled score #$index assigned to ${attributes.joinToString { it.abbreviation }}"
    }

    data class ScoreIndexOutOfRange(val attribute: Attribute, val index: Int) : CreationError {
        override val message get() = "${attribute.abbreviation} assigned to nonexistent roll #$index"
    }

    data class UnknownBackground(val id: BackgroundId) : CreationError {
        override val message get() = "No starting background '$id'"
    }

    data class InvalidName(val name: String) : CreationError {
        override val message get() = "Character name must be 1..40 characters"
    }
}

object AttributeAssignment {
    /**
     * [choice] maps each attribute to the index (0..5) of a rolled score. Indices, not values, so
     * two equal rolls are still distinguishable and each roll is used exactly once.
     */
    fun assign(rolled: RolledAttributeSet, choice: Map<Attribute, Int>): Validated<AttributeScores, CreationError> {
        val errors = mutableListOf<CreationError>()
        Attribute.entries.filter { it !in choice }.forEach { errors += CreationError.MissingAttribute(it) }
        choice.forEach { (attribute, index) ->
            if (index !in rolled.scores.indices) errors += CreationError.ScoreIndexOutOfRange(attribute, index)
        }
        choice.entries.groupBy({ it.value }, { it.key })
            .filter { it.value.size > 1 }
            .forEach { (index, attributes) -> errors += CreationError.ScoreUsedTwice(index, attributes.sortedBy { it.ordinal }) }
        if (errors.isNotEmpty()) return Validated.Invalid(errors)
        return Validated.Valid(AttributeScores(choice.mapValues { (_, index) -> rolled.scores[index] }))
    }
}

/**
 * A starting background. It only *seeds* a character: some starting skill XP, kit, gold and a
 * story tie. It has no field that could restrict a skill, and none is consulted after creation.
 */
data class Background(
    val id: BackgroundId,
    val name: String,
    val summary: String,
    val startingSkillXp: Map<SkillId, Int>,
    val startingItems: Map<ItemTemplateId, Int>,
    val startingGold: Int,
    val narrativeTie: String,
)

/**
 * Starter backgrounds. PROVISIONAL roster and bonuses: each grants the same budget (three skills
 * to level 2, 15 gold, kit worth ~15–20 gold) so no choice is strictly better.
 */
object Backgrounds {
    val CARAVAN_GUARD = BackgroundId("caravan_guard")
    val HEDGE_APPRENTICE = BackgroundId("hedge_apprentice")
    val STREET_RUNNER = BackgroundId("street_runner")
    val WANDERING_MINSTREL = BackgroundId("wandering_minstrel")

    const val STARTING_SKILL_XP = 10
    const val STARTING_GOLD = 15

    val all: List<Background> = listOf(
        Background(
            CARAVAN_GUARD, "Caravan Guard", "Years walking beside wagons on dangerous roads.",
            startingSkillXp = mapOf(SkillCatalog.MELEE to 10, SkillCatalog.ATHLETICS to 10, SkillCatalog.PERCEPTION to 10),
            startingItems = mapOf(ContentCatalog.SHORT_SWORD to 1, ContentCatalog.LEATHER_ARMOR to 1),
            startingGold = STARTING_GOLD,
            narrativeTie = "A merchant house still owes you a season's wages.",
        ),
        Background(
            HEDGE_APPRENTICE, "Hedge Apprentice", "Taught a little real magic by a village wise-woman.",
            startingSkillXp = mapOf(SkillCatalog.MAGIC to 10, SkillCatalog.LORE to 10, SkillCatalog.INSIGHT to 10),
            startingItems = mapOf(ContentCatalog.QUARTERSTAFF to 1, ContentCatalog.HEALING_DRAUGHT to 2),
            startingGold = STARTING_GOLD,
            narrativeTie = "Your teacher vanished, leaving a half-finished letter addressed to you.",
        ),
        Background(
            STREET_RUNNER, "Street Runner", "Grew up quick-fingered and quicker-footed in a market town.",
            startingSkillXp = mapOf(SkillCatalog.STEALTH to 10, SkillCatalog.LOCKPICKING to 10, SkillCatalog.DECEPTION to 10),
            startingItems = mapOf(ContentCatalog.DAGGER to 1, ContentCatalog.LOCKPICKS to 1),
            startingGold = STARTING_GOLD,
            narrativeTie = "Someone from your old crew knows your real name.",
        ),
        Background(
            WANDERING_MINSTREL, "Wandering Minstrel", "You trade songs for supper and rumours for coin.",
            startingSkillXp = mapOf(SkillCatalog.PERSUASION to 10, SkillCatalog.INSIGHT to 10, SkillCatalog.LORE to 10),
            startingItems = mapOf(ContentCatalog.LUTE to 1, ContentCatalog.DAGGER to 1),
            startingGold = STARTING_GOLD,
            narrativeTie = "A song you wrote has made a powerful person angry.",
        ),
    )

    private val byId = all.associateBy { it.id }

    operator fun get(id: BackgroundId): Background? = byId[id]
}

class CharacterFactory(
    private val balance: BalanceConfig = BalanceConfig.DEFAULT,
    private val catalog: ContentCatalog = ContentCatalog.STARTER,
) {
    init {
        Backgrounds.all.forEach { bg ->
            bg.startingSkillXp.keys.forEach { require(SkillCatalog[it] != null) { "${bg.id}: unknown skill $it" } }
            bg.startingItems.keys.forEach { require(catalog.item(it) != null) { "${bg.id}: unknown item $it" } }
        }
    }

    fun create(
        name: String,
        rolled: RolledAttributeSet,
        assignment: Map<Attribute, Int>,
        backgroundId: BackgroundId,
    ): Validated<PlayerCharacter, CreationError> {
        val errors = mutableListOf<CreationError>()
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > 40) errors += CreationError.InvalidName(name)
        val background = Backgrounds[backgroundId]
        if (background == null) errors += CreationError.UnknownBackground(backgroundId)
        val scores = when (val result = AttributeAssignment.assign(rolled, assignment)) {
            is Validated.Invalid -> {
                errors += result.errors
                null
            }
            is Validated.Valid -> result.value
        }
        if (errors.isNotEmpty() || scores == null || background == null) return Validated.Invalid(errors)

        val skillXp = SkillCatalog.all.associate { it.id to (background.startingSkillXp[it.id] ?: 0) }
        val maxHp = balance.vitals.maxHp(scores)
        val maxMp = balance.vitals.maxMp(scores)
        return Validated.Valid(
            PlayerCharacter(
                id = PLAYER_ID,
                name = trimmed,
                background = background.id,
                attributes = scores,
                skillXp = skillXp,
                vitals = Vitals(maxHp, maxHp, maxMp, maxMp),
                gold = background.startingGold,
                inventory = background.startingItems,
            ),
        )
    }

    companion object {
        /** Single-player: the player character's id is constant within a campaign. */
        val PLAYER_ID = CharacterId("pc")
    }
}

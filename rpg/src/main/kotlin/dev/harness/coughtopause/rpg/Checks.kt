package dev.harness.coughtopause.rpg

/**
 * Difficulty is chosen from a fixed ladder before anything is rolled. The DC for each rolled tier
 * comes from [CheckRules], never from the narrator.
 */
enum class DifficultyTier {
    /** Always succeeds, no roll, no XP. */
    TRIVIAL,
    EASY,
    MODERATE,
    HARD,
    VERY_HARD,
    HEROIC,

    /** Never succeeds and never rolls, however the attempt is narrated. */
    IMPOSSIBLE,
    ;

    val rolls: Boolean get() = this != TRIVIAL && this != IMPOSSIBLE
}

enum class ModifierSource {
    /** Derived by the engine from carried items. Requests may not supply these. */
    EQUIPMENT,

    /** Situational advantage/disadvantage proposed with the request, bounded by [CheckRules]. */
    CIRCUMSTANCE,
}

data class Modifier(val source: ModifierSource, val label: String, val value: Int)

/** Important rolls are announced aloud; routine ones are recorded silently. Both are kept. */
enum class RollImportance {
    ROUTINE,
    IMPORTANT,
}

/** Prerequisites. If any is unmet the action is impossible: no roll, no luck, no XP. */
sealed interface Requirement {
    data class HasItem(val item: ItemTemplateId, val quantity: Int = 1) : Requirement
    data class MinimumSkillLevel(val skill: SkillId, val level: Int) : Requirement
}

/** A proposed check. Everything here is validated before a die is touched. */
data class CheckRequest(
    val skill: SkillId,
    val difficulty: DifficultyTier,
    val challenge: ChallengeKey,
    /** Short label of what is being attempted, for the roll log ("pick the cellar lock"). */
    val intent: String,
    /** Overrides the skill's default attribute when the approach calls for it. */
    val attribute: Attribute? = null,
    val circumstances: List<Modifier> = emptyList(),
    val requirements: List<Requirement> = emptyList(),
    val importance: RollImportance = RollImportance.ROUTINE,
)

enum class CheckOutcome {
    SUCCESS,
    FAILURE,

    /** Trivial, or the character's bonus makes failure impossible. No die rolled. */
    AUTOMATIC_SUCCESS,

    /** Declared impossible, prerequisite unmet, or the DC is out of reach. No die rolled. */
    IMPOSSIBLE,
}

sealed interface CheckError : DomainError {
    data class UnknownSkill(val skill: SkillId) : CheckError {
        override val message get() = "Skill '$skill' is not in the skill catalog"
    }

    data class SkillNotPlayable(val skill: SkillId) : CheckError {
        override val message get() = "Skill '$skill' exists but cannot be used yet"
    }

    data class ModifierSourceNotAllowed(val label: String) : CheckError {
        override val message get() = "Equipment modifier '$label' must come from inventory, not the request"
    }

    data class ModifierOutOfBounds(val label: String, val value: Int, val max: Int) : CheckError {
        override val message get() = "Modifier '$label' ($value) exceeds ±$max"
    }

    data class ModifierTotalOutOfBounds(val source: ModifierSource, val total: Int, val max: Int) : CheckError {
        override val message get() = "$source modifiers total $total, limit ±$max"
    }

    data class InvalidText(val field: String) : CheckError {
        override val message get() = "$field must be 1..120 characters"
    }

    data class InvalidRequirement(val detail: String) : CheckError {
        override val message get() = "Invalid requirement: $detail"
    }
}

/**
 * A check whose difficulty and modifiers are fixed. It has no copy/mutate API: the only thing
 * that can happen to it is [CheckEngine.resolve]. Rolling therefore cannot precede, or alter,
 * the difficulty.
 */
class PreparedCheck internal constructor(
    val request: CheckRequest,
    val attribute: Attribute,
    val attributeModifier: Int,
    val skillLevel: Int,
    val skillBonus: Int,
    val equipment: List<Modifier>,
    val difficultyClass: Int?,
    val unmetRequirements: List<Requirement>,
) {
    val modifiers: List<Modifier> get() = equipment + request.circumstances

    val totalModifier: Int get() = attributeModifier + skillBonus + modifiers.sumOf { it.value }
}

/** The inspectable audit record of one check, kept forever in the event history. */
data class RollRecord(
    val id: RollId,
    val actionId: ActionId,
    val skill: SkillId,
    val attribute: Attribute,
    val intent: String,
    val challenge: ChallengeKey,
    val difficulty: DifficultyTier,
    val difficultyClass: Int?,
    /** The raw d20, or null when no roll was needed or allowed. */
    val natural: Int?,
    val attributeModifier: Int,
    val skillLevel: Int,
    val skillBonus: Int,
    val modifiers: List<Modifier>,
    val total: Int?,
    val outcome: CheckOutcome,
    val explanation: String,
    val importance: RollImportance,
)

class CheckEngine(
    private val rules: CheckRules,
    private val catalog: ContentCatalog,
    private val curve: XpCurve,
) {

    /** Validates a request against the character and fixes DC + modifiers. Never rolls. */
    fun prepare(character: PlayerCharacter, request: CheckRequest): Validated<PreparedCheck, CheckError> {
        val errors = mutableListOf<CheckError>()
        val skill = SkillCatalog[request.skill]
        when {
            skill == null -> errors += CheckError.UnknownSkill(request.skill)
            !skill.playable -> errors += CheckError.SkillNotPlayable(request.skill)
        }
        if (request.intent.isBlank() || request.intent.length > 120) errors += CheckError.InvalidText("intent")

        request.circumstances.forEach { m ->
            if (m.source != ModifierSource.CIRCUMSTANCE) errors += CheckError.ModifierSourceNotAllowed(m.label)
            if (m.label.isBlank() || m.label.length > 120) errors += CheckError.InvalidText("modifier label")
            if (kotlin.math.abs(m.value) > rules.maxModifierMagnitude) {
                errors += CheckError.ModifierOutOfBounds(m.label, m.value, rules.maxModifierMagnitude)
            }
        }
        val circumstanceTotal = request.circumstances.sumOf { it.value }
        if (kotlin.math.abs(circumstanceTotal) > rules.maxCircumstanceTotal) {
            errors += CheckError.ModifierTotalOutOfBounds(
                ModifierSource.CIRCUMSTANCE, circumstanceTotal, rules.maxCircumstanceTotal,
            )
        }
        request.requirements.forEach { r ->
            when (r) {
                is Requirement.HasItem -> {
                    if (catalog.item(r.item) == null) errors += CheckError.InvalidRequirement("unknown item ${r.item}")
                    if (r.quantity < 1) errors += CheckError.InvalidRequirement("quantity must be >= 1")
                }
                is Requirement.MinimumSkillLevel -> {
                    if (SkillCatalog[r.skill] == null) errors += CheckError.InvalidRequirement("unknown skill ${r.skill}")
                    if (r.level !in MIN_SKILL_LEVEL..MAX_SKILL_LEVEL) {
                        errors += CheckError.InvalidRequirement("skill level must be 1..20")
                    }
                }
            }
        }
        if (errors.isNotEmpty() || skill == null) return Validated.Invalid(errors)

        val attribute = request.attribute ?: skill.defaultAttribute
        val level = curve.levelFor(character.skillXp[request.skill] ?: 0)
        val equipment = equipmentModifiers(character, request.skill)
        val unmet = request.requirements.filterNot { isMet(it, character) }
        return Validated.Valid(
            PreparedCheck(
                request = request,
                attribute = attribute,
                attributeModifier = character.attributes.modifier(attribute),
                skillLevel = level,
                skillBonus = rules.skillBonus(level),
                equipment = equipment,
                difficultyClass = rules.difficultyClass(request.difficulty),
                unmetRequirements = unmet,
            ),
        )
    }

    /** Rolls (if a roll is legal at all) and produces the audit record. */
    fun resolve(check: PreparedCheck, rng: RandomSource, rollId: RollId, actionId: ActionId): RollRecord {
        val request = check.request
        val dc = check.difficultyClass
        val bonus = check.totalModifier
        fun record(outcome: CheckOutcome, natural: Int?, explanation: String) = RollRecord(
            id = rollId,
            actionId = actionId,
            skill = request.skill,
            attribute = check.attribute,
            intent = request.intent,
            challenge = request.challenge,
            difficulty = request.difficulty,
            difficultyClass = dc,
            natural = natural,
            attributeModifier = check.attributeModifier,
            skillLevel = check.skillLevel,
            skillBonus = check.skillBonus,
            modifiers = check.modifiers,
            total = natural?.let { it + bonus },
            outcome = outcome,
            explanation = explanation,
            importance = request.importance,
        )

        return when {
            request.difficulty == DifficultyTier.IMPOSSIBLE ->
                record(CheckOutcome.IMPOSSIBLE, null, "Declared impossible; no roll")
            check.unmetRequirements.isNotEmpty() ->
                record(CheckOutcome.IMPOSSIBLE, null, "Missing prerequisite: " + check.unmetRequirements.joinToString { describe(it) })
            request.difficulty == DifficultyTier.TRIVIAL || dc == null ->
                record(CheckOutcome.AUTOMATIC_SUCCESS, null, "Trivial; no roll")
            1 + bonus >= dc ->
                record(CheckOutcome.AUTOMATIC_SUCCESS, null, "Cannot fail (bonus %+d vs DC %d); no roll".format(bonus, dc))
            CHECK_DIE_SIDES + bonus < dc ->
                record(CheckOutcome.IMPOSSIBLE, null, "Out of reach (bonus %+d vs DC %d); no roll".format(bonus, dc))
            else -> {
                val natural = Dice.roll(rng, CHECK_DIE_SIDES)
                val total = natural + bonus
                val outcome = if (total >= dc) CheckOutcome.SUCCESS else CheckOutcome.FAILURE
                record(outcome, natural, "d20 %d %+d = %d vs DC %d".format(natural, bonus, total, dc))
            }
        }
    }

    private fun equipmentModifiers(character: PlayerCharacter, skill: SkillId): List<Modifier> {
        val best = character.inventory.keys
            .mapNotNull { catalog.item(it) }
            .mapNotNull { item -> item.skillBonuses[skill]?.let { item to it } }
            .maxByOrNull { it.second }
            ?: return emptyList()
        return listOf(Modifier(ModifierSource.EQUIPMENT, best.first.name, minOf(best.second, rules.maxEquipmentTotal)))
    }

    private fun isMet(requirement: Requirement, character: PlayerCharacter): Boolean = when (requirement) {
        is Requirement.HasItem -> (character.inventory[requirement.item] ?: 0) >= requirement.quantity
        is Requirement.MinimumSkillLevel -> curve.levelFor(character.skillXp[requirement.skill] ?: 0) >= requirement.level
    }

    private fun describe(requirement: Requirement): String = when (requirement) {
        is Requirement.HasItem -> "${requirement.quantity}× ${catalog.item(requirement.item)?.name ?: requirement.item}"
        is Requirement.MinimumSkillLevel -> "${SkillCatalog[requirement.skill]?.name ?: requirement.skill} level ${requirement.level}"
    }
}

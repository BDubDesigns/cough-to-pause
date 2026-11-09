package dev.harness.coughtopause.rpg

/*
 * Every number in this file is a PROVISIONAL balance default, not an approved design decision.
 * They exist so the rules are testable today and can be tuned after playtesting without touching
 * rule code. docs/rpg-domain.md lists them for sign-off.
 *
 * Locked requirements (not tunable here): six attributes, 4d6-drop-lowest, skills 1..20, d20 checks.
 */

/** Skill levels run 1..20. This is a locked design decision, not a balance knob. */
const val MIN_SKILL_LEVEL = 1
const val MAX_SKILL_LEVEL = 20

/** The check die. d20 resolution is locked. */
const val CHECK_DIE_SIDES = 20

/**
 * Poor-roll safeguard: a rolled set is discarded and rerolled unless it meets BOTH thresholds.
 * PROVISIONAL: 66 total / highest ≥ 14 rejects roughly one set in eight.
 */
data class AttributeSafeguard(
    val minimumTotal: Int = 66,
    val minimumHighestScore: Int = 14,
    /** After this many rerolls the best rejected set is kept, so creation always terminates. */
    val maxRerolls: Int = 20,
) {
    init {
        require(maxRerolls >= 0) { "maxRerolls must be >= 0" }
    }

    fun accepts(scores: List<Int>): Boolean =
        scores.sum() >= minimumTotal && scores.max() >= minimumHighestScore
}

/**
 * XP needed to reach level L is `step * (L-1) * L / 2`: level 2 at 10, level 5 at 100,
 * level 10 at 450, level 20 at 1900. PROVISIONAL, tuned for short campaigns, not grinding.
 */
data class XpCurve(val xpPerLevelStep: Int = 10) {
    init {
        require(xpPerLevelStep > 0) { "xpPerLevelStep must be positive" }
    }

    fun totalXpFor(level: Int): Int {
        require(level in MIN_SKILL_LEVEL..MAX_SKILL_LEVEL) { "level $level outside 1..20" }
        return xpPerLevelStep * (level - 1) * level / 2
    }

    /** XP is never stored above this: it is exactly the level-20 threshold. */
    val maxXp: Int get() = totalXpFor(MAX_SKILL_LEVEL)

    fun levelFor(xp: Int): Int {
        require(xp >= 0) { "xp cannot be negative" }
        var level = MIN_SKILL_LEVEL
        while (level < MAX_SKILL_LEVEL && xp >= totalXpFor(level + 1)) level++
        return level
    }
}

data class CheckRules(
    /** Skill bonus = level / this. With 4, level 1–3 → +0 … level 20 → +5 (never +20). */
    val skillLevelsPerBonusPoint: Int = 4,
    /** No single equipment or circumstance modifier may exceed ±this. */
    val maxModifierMagnitude: Int = 5,
    /** Sum of equipment modifiers must stay within ±this. */
    val maxEquipmentTotal: Int = 3,
    /** Sum of circumstance modifiers must stay within ±this. */
    val maxCircumstanceTotal: Int = 5,
    /** Difficulty class per rolled tier. TRIVIAL and IMPOSSIBLE never roll. */
    val difficultyClasses: Map<DifficultyTier, Int> = mapOf(
        DifficultyTier.EASY to 8,
        DifficultyTier.MODERATE to 12,
        DifficultyTier.HARD to 15,
        DifficultyTier.VERY_HARD to 18,
        DifficultyTier.HEROIC to 21,
    ),
) {
    init {
        require(skillLevelsPerBonusPoint > 0)
        val rolled = DifficultyTier.entries.filter { it.rolls }.toSet()
        require(difficultyClasses.keys == rolled) { "Every rolled tier needs a DC: $rolled" }
    }

    fun skillBonus(level: Int): Int = level / skillLevelsPerBonusPoint

    fun difficultyClass(tier: DifficultyTier): Int? = difficultyClasses[tier]
}

data class XpRewards(
    /** XP for a successful meaningful check, by tier. */
    val successXp: Map<DifficultyTier, Int> = mapOf(
        DifficultyTier.EASY to 4,
        DifficultyTier.MODERATE to 8,
        DifficultyTier.HARD to 12,
        DifficultyTier.VERY_HARD to 16,
        DifficultyTier.HEROIC to 20,
    ),
    /** A meaningful failure earns this percentage of the success award (minimum 1). */
    val failurePercent: Int = 50,
    /**
     * A skill can earn XP from at most this many distinct challenge topics on one subject
     * (NPC, location, creature) — a backstop against farming by rephrasing.
     */
    val maxTopicsPerSubjectPerSkill: Int = 3,
) {
    init {
        require(failurePercent in 0..100)
        require(maxTopicsPerSubjectPerSkill >= 1)
    }

    fun forSuccess(tier: DifficultyTier): Int = successXp[tier] ?: 0

    fun forFailure(tier: DifficultyTier): Int {
        val success = forSuccess(tier)
        return if (success == 0 || failurePercent == 0) 0 else maxOf(1, success * failurePercent / 100)
    }
}

/** Starting HP/MP from attributes. */
data class VitalsFormula(
    val baseHp: Int = 10,
    val hpPerConstitutionModifier: Int = 2,
    val minimumHp: Int = 4,
    val baseMp: Int = 4,
    val mpPerIntelligenceModifier: Int = 2,
    val minimumMp: Int = 2,
) {
    fun maxHp(scores: AttributeScores): Int =
        maxOf(minimumHp, baseHp + hpPerConstitutionModifier * scores.modifier(Attribute.CONSTITUTION))

    fun maxMp(scores: AttributeScores): Int =
        maxOf(minimumMp, baseMp + mpPerIntelligenceModifier * scores.modifier(Attribute.INTELLIGENCE))
}

/**
 * Creature XP is derived from level and threat, never stored per template, so two same-level,
 * same-threat creatures always pay the same regardless of their name.
 */
data class CreatureXp(
    val xpPerLevel: Int = 5,
    val threatMultiplier: Map<ThreatTier, Int> = mapOf(
        ThreatTier.MINION to 1,
        ThreatTier.STANDARD to 2,
        ThreatTier.ELITE to 4,
    ),
) {
    fun forCreature(level: Int, threat: ThreatTier): Int = xpPerLevel * level * threatMultiplier.getValue(threat)
}

/** Size and text budgets for AI-proposed world content. */
data class WorldLimits(
    val maxLocationsPerProposal: Int = 8,
    val maxNpcsPerProposal: Int = 6,
    val maxFactsPerProposal: Int = 12,
    val maxCreaturesPerLocation: Int = 3,
    val minInitialNpcs: Int = 2,
    val maxNameLength: Int = 60,
    val maxDescriptionLength: Int = 600,
    val maxFactLength: Int = 300,
)

data class BalanceConfig(
    val attributeSafeguard: AttributeSafeguard = AttributeSafeguard(),
    val xpCurve: XpCurve = XpCurve(),
    val checks: CheckRules = CheckRules(),
    val xpRewards: XpRewards = XpRewards(),
    val vitals: VitalsFormula = VitalsFormula(),
    val creatureXp: CreatureXp = CreatureXp(),
    val world: WorldLimits = WorldLimits(),
    /** Largest group StartEncounter accepts. */
    val maxEnemiesPerEncounter: Int = 4,
    val combat: CombatRules = CombatRules(),
) {
    companion object {
        val DEFAULT = BalanceConfig()
    }
}

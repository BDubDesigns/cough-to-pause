package dev.harness.coughtopause.rpg

enum class SkillCategory {
    COMBAT,
    MAGIC,
    SOCIAL,
    EXPLORATION,
    PROFESSION,
}

/**
 * One entry in the fixed skill catalog. [defaultAttribute] is what a check uses unless the
 * request names a different attribute (e.g. Intimidation with STR).
 * [playable] = false marks future profession entries that exist but cannot be checked in M1.
 */
data class SkillDefinition(
    val id: SkillId,
    val name: String,
    val category: SkillCategory,
    val defaultAttribute: Attribute,
    val playable: Boolean = true,
)

/**
 * The complete, fixed skill registry. The AI cannot add to it: any check naming a skill that is
 * not listed here is rejected. The roster itself is PROVISIONAL (not yet approved).
 */
object SkillCatalog {
    val MELEE = SkillId("melee")
    val RANGED = SkillId("ranged")
    val DEFENSE = SkillId("defense")
    val MAGIC = SkillId("magic")
    val STEALTH = SkillId("stealth")
    val PERCEPTION = SkillId("perception")
    val LOCKPICKING = SkillId("lockpicking")
    val ATHLETICS = SkillId("athletics")
    val SURVIVAL = SkillId("survival")
    val LORE = SkillId("lore")
    val PERSUASION = SkillId("persuasion")
    val DECEPTION = SkillId("deception")
    val INTIMIDATION = SkillId("intimidation")
    val INSIGHT = SkillId("insight")
    val MINING = SkillId("mining")
    val HERBALISM = SkillId("herbalism")
    val SMITHING = SkillId("smithing")

    val all: List<SkillDefinition> = listOf(
        SkillDefinition(MELEE, "Melee", SkillCategory.COMBAT, Attribute.STRENGTH),
        SkillDefinition(RANGED, "Ranged", SkillCategory.COMBAT, Attribute.DEXTERITY),
        SkillDefinition(DEFENSE, "Defense", SkillCategory.COMBAT, Attribute.CONSTITUTION),
        SkillDefinition(MAGIC, "Magic", SkillCategory.MAGIC, Attribute.INTELLIGENCE),
        SkillDefinition(STEALTH, "Stealth", SkillCategory.EXPLORATION, Attribute.DEXTERITY),
        SkillDefinition(PERCEPTION, "Perception", SkillCategory.EXPLORATION, Attribute.WISDOM),
        SkillDefinition(LOCKPICKING, "Lockpicking", SkillCategory.EXPLORATION, Attribute.DEXTERITY),
        SkillDefinition(ATHLETICS, "Athletics", SkillCategory.EXPLORATION, Attribute.STRENGTH),
        SkillDefinition(SURVIVAL, "Survival", SkillCategory.EXPLORATION, Attribute.WISDOM),
        SkillDefinition(LORE, "Lore", SkillCategory.EXPLORATION, Attribute.INTELLIGENCE),
        SkillDefinition(PERSUASION, "Persuasion", SkillCategory.SOCIAL, Attribute.CHARISMA),
        SkillDefinition(DECEPTION, "Deception", SkillCategory.SOCIAL, Attribute.CHARISMA),
        SkillDefinition(INTIMIDATION, "Intimidation", SkillCategory.SOCIAL, Attribute.CHARISMA),
        SkillDefinition(INSIGHT, "Insight", SkillCategory.SOCIAL, Attribute.WISDOM),
        SkillDefinition(MINING, "Mining", SkillCategory.PROFESSION, Attribute.STRENGTH, playable = false),
        SkillDefinition(HERBALISM, "Herbalism", SkillCategory.PROFESSION, Attribute.WISDOM, playable = false),
        SkillDefinition(SMITHING, "Smithing", SkillCategory.PROFESSION, Attribute.STRENGTH, playable = false),
    )

    private val byId: Map<SkillId, SkillDefinition> = all.associateBy { it.id }

    init {
        require(byId.size == all.size) { "Duplicate skill id in catalog" }
    }

    val ids: Set<SkillId> get() = byId.keys

    operator fun get(id: SkillId): SkillDefinition? = byId[id]
}

/**
 * What a check is *about*, for anti-repeat purposes. [subjectId] must be an entity the engine
 * knows (location, NPC, encounter, creature instance). [topic] names the obstacle on that subject,
 * e.g. ("npc-0005", "disappearances"). The orchestrator composes it, not the narrator; when the
 * obstacle genuinely changes, the orchestrator uses a new topic.
 */
data class ChallengeKey(val subjectId: String, val topic: String) {
    init {
        require(subjectId.isNotBlank() && subjectId.length <= 64) { "challenge subject must be 1..64 chars" }
        require(TOPIC.matches(topic)) { "challenge topic must match ${TOPIC.pattern}" }
    }

    private companion object {
        val TOPIC = Regex("[a-z0-9][a-z0-9_-]{0,47}")
    }
}

/** Ledger entry key: XP already awarded for this skill against this exact challenge. */
data class LedgerKey(val skill: SkillId, val subjectId: String, val topic: String)

enum class XpReason {
    SUCCESS,
    MEANINGFUL_FAILURE,

    /** No roll was needed (trivial, or the character could not fail). */
    TRIVIAL,

    /** The attempt never happened (declared impossible, missing prerequisite, unreachable DC). */
    NOT_ATTEMPTED,

    /** The same unchanged challenge already paid out everything it can. */
    REPEATED_CHALLENGE,

    /** This skill already earned XP from the maximum number of topics on this subject. */
    SUBJECT_LIMIT_REACHED,

    /** The skill is at level 20. */
    SKILL_AT_CAP,
}

data class XpDecision(val amount: Int, val reason: XpReason)

/**
 * The meaningful-action XP contract.
 *
 * - Success pays the tier's award; a meaningful failure pays a fraction of it.
 * - Each (skill, subject, topic) pays at most the success award in total: failing then
 *   succeeding tops up to the success amount, failing twice pays once, succeeding twice pays once.
 * - No-roll outcomes pay nothing.
 * - XP never exceeds the level-20 threshold.
 */
class XpAwardPolicy(private val rewards: XpRewards, private val curve: XpCurve) {

    fun decide(
        skill: SkillId,
        tier: DifficultyTier,
        outcome: CheckOutcome,
        challenge: ChallengeKey,
        currentXp: Int,
        ledger: Map<LedgerKey, Int>,
    ): XpDecision {
        val entitled = when (outcome) {
            CheckOutcome.SUCCESS -> rewards.forSuccess(tier)
            CheckOutcome.FAILURE -> rewards.forFailure(tier)
            CheckOutcome.AUTOMATIC_SUCCESS -> return XpDecision(0, XpReason.TRIVIAL)
            CheckOutcome.IMPOSSIBLE -> return XpDecision(0, XpReason.NOT_ATTEMPTED)
        }
        val key = LedgerKey(skill, challenge.subjectId, challenge.topic)
        val already = ledger[key]
        if (already == null) {
            val topicsOnSubject = ledger.keys.count { it.skill == skill && it.subjectId == challenge.subjectId }
            if (topicsOnSubject >= rewards.maxTopicsPerSubjectPerSkill) {
                return XpDecision(0, XpReason.SUBJECT_LIMIT_REACHED)
            }
        }
        val remaining = entitled - (already ?: 0)
        if (remaining <= 0) return XpDecision(0, XpReason.REPEATED_CHALLENGE)
        val headroom = curve.maxXp - currentXp
        if (headroom <= 0) return XpDecision(0, XpReason.SKILL_AT_CAP)
        val reason = if (outcome == CheckOutcome.SUCCESS) XpReason.SUCCESS else XpReason.MEANINGFUL_FAILURE
        return XpDecision(minOf(remaining, headroom), reason)
    }
}

package dev.harness.coughtopause.rpg

data class DamageDice(val count: Int, val sides: Int, val bonus: Int = 0) {
    init {
        require(count in 1..10) { "damage dice count must be 1..10" }
        require(sides in 2..20) { "damage die must have 2..20 sides" }
        require(bonus in -5..10) { "damage bonus must be -5..10" }
    }

    override fun toString(): String = "${count}d$sides" + if (bonus != 0) "%+d".format(bonus) else ""
}

enum class ItemKind {
    WEAPON,
    ARMOR,
    TOOL,
    CONSUMABLE,
    TRINKET,
}

/**
 * Canonical item definition. Inventories hold template ids + counts; the template is the only
 * place an item's mechanics live, so the narrator cannot give a "sword" new powers.
 */
data class ItemTemplate(
    val id: ItemTemplateId,
    val name: String,
    val kind: ItemKind,
    val valueGold: Int,
    /** Bonus to checks with these skills while carried. Best item wins; bonuses do not stack. */
    val skillBonuses: Map<SkillId, Int> = emptyMap(),
    val damage: DamageDice? = null,
    val armorBonus: Int = 0,
    val healsHp: Int = 0,
)

enum class ThreatTier {
    MINION,
    STANDARD,
    ELITE,
}

data class LootEntry(val item: ItemTemplateId, val chancePercent: Int, val minQuantity: Int = 1, val maxQuantity: Int = 1) {
    init {
        require(chancePercent in 1..100) { "loot chance must be 1..100%" }
        require(minQuantity in 1..maxQuantity) { "loot quantity range invalid" }
    }
}

/** Canonical creature definition, reused every time this kind of creature appears. */
data class CreatureTemplate(
    val id: CreatureTemplateId,
    val name: String,
    val level: Int,
    val threat: ThreatTier,
    val maxHp: Int,
    val defense: Int,
    val attackBonus: Int,
    val damage: DamageDice,
    val goldMin: Int,
    val goldMax: Int,
    val loot: List<LootEntry>,
) {
    init {
        require(level in MIN_SKILL_LEVEL..MAX_SKILL_LEVEL) { "creature level must be 1..20" }
        require(maxHp > 0) { "creature needs HP" }
        require(goldMin in 0..goldMax) { "gold range invalid" }
    }
}

/** The fixed, validated set of item and creature templates a campaign may reference. */
class ContentCatalog(items: List<ItemTemplate>, creatures: List<CreatureTemplate>, checks: CheckRules = CheckRules()) {

    val items: Map<ItemTemplateId, ItemTemplate> = items.associateBy { it.id }
    val creatures: Map<CreatureTemplateId, CreatureTemplate> = creatures.associateBy { it.id }

    init {
        require(this.items.size == items.size) { "Duplicate item template id" }
        require(this.creatures.size == creatures.size) { "Duplicate creature template id" }
        items.forEach { item ->
            item.skillBonuses.forEach { (skill, bonus) ->
                require(SkillCatalog[skill] != null) { "${item.id} boosts unknown skill $skill" }
                require(bonus in 1..checks.maxEquipmentTotal) { "${item.id} bonus $bonus out of bounds" }
            }
        }
        creatures.forEach { creature ->
            creature.loot.forEach { entry ->
                require(entry.item in this.items) { "${creature.id} drops unknown item ${entry.item}" }
            }
        }
    }

    fun item(id: ItemTemplateId): ItemTemplate? = items[id]

    fun creature(id: CreatureTemplateId): CreatureTemplate? = creatures[id]

    companion object {
        val SHORT_SWORD = ItemTemplateId("short_sword")
        val DAGGER = ItemTemplateId("dagger")
        val QUARTERSTAFF = ItemTemplateId("quarterstaff")
        val LEATHER_ARMOR = ItemTemplateId("leather_armor")
        val LOCKPICKS = ItemTemplateId("lockpicks")
        val HEALING_DRAUGHT = ItemTemplateId("healing_draught")
        val LUTE = ItemTemplateId("lute")
        val GOBLIN_EAR = ItemTemplateId("goblin_ear")
        val COPPER_RING = ItemTemplateId("copper_ring")

        val GOBLIN = CreatureTemplateId("goblin")
        val GIANT_RAT = CreatureTemplateId("giant_rat")

        /** Small known-good seed catalog. All stats are PROVISIONAL. */
        val STARTER = ContentCatalog(
            items = listOf(
                ItemTemplate(SHORT_SWORD, "Short sword", ItemKind.WEAPON, 10, damage = DamageDice(1, 6)),
                ItemTemplate(DAGGER, "Dagger", ItemKind.WEAPON, 4, damage = DamageDice(1, 4)),
                ItemTemplate(QUARTERSTAFF, "Quarterstaff", ItemKind.WEAPON, 2, damage = DamageDice(1, 6)),
                ItemTemplate(LEATHER_ARMOR, "Leather armor", ItemKind.ARMOR, 10, armorBonus = 1),
                ItemTemplate(
                    LOCKPICKS, "Lockpicks", ItemKind.TOOL, 8,
                    skillBonuses = mapOf(SkillCatalog.LOCKPICKING to 2),
                ),
                ItemTemplate(HEALING_DRAUGHT, "Healing draught", ItemKind.CONSUMABLE, 5, healsHp = 8),
                ItemTemplate(LUTE, "Lute", ItemKind.TOOL, 8, skillBonuses = mapOf(SkillCatalog.PERSUASION to 1)),
                ItemTemplate(GOBLIN_EAR, "Goblin ear", ItemKind.TRINKET, 1),
                ItemTemplate(COPPER_RING, "Copper ring", ItemKind.TRINKET, 5),
            ),
            creatures = listOf(
                CreatureTemplate(
                    GOBLIN, "Goblin", level = 1, threat = ThreatTier.STANDARD, maxHp = 7, defense = 12,
                    attackBonus = 3, damage = DamageDice(1, 6), goldMin = 1, goldMax = 6,
                    loot = listOf(LootEntry(GOBLIN_EAR, 60), LootEntry(DAGGER, 20), LootEntry(COPPER_RING, 10)),
                ),
                CreatureTemplate(
                    GIANT_RAT, "Giant rat", level = 1, threat = ThreatTier.MINION, maxHp = 4, defense = 10,
                    attackBonus = 2, damage = DamageDice(1, 4), goldMin = 0, goldMax = 0,
                    loot = emptyList(),
                ),
            ),
        )
    }
}

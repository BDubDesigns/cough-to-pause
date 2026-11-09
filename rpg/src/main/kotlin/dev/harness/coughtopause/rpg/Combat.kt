package dev.harness.coughtopause.rpg

/** What a spell does. Two kinds is the whole first slice. */
enum class SpellKind {
    /** Damage one enemy, costs MP. */
    DAMAGE,

    /** Learn a creature's catalogue entry: costs MP the first time, free afterwards. */
    IDENTIFY,
}

data class SpellTemplate(
    val id: SpellId,
    val name: String,
    val kind: SpellKind,
    val mpCost: Int,
    /** Only for [SpellKind.DAMAGE]; the attribute modifier is added by the rules. */
    val damage: DamageDice? = null,
) {
    init {
        require(mpCost >= 0) { "spell mp cost cannot be negative" }
        if (kind == SpellKind.DAMAGE) requireNotNull(damage) { "a damage spell needs dice" }
    }
}

/**
 * The spell catalogue. Deliberately tiny: one damage spell and Identify, as scoped in #7.
 * New spells are catalogue entries, never new fields on a character.
 */
object SpellCatalog {
    val EMBER_BOLT = SpellId("ember_bolt")
    val IDENTIFY = SpellId("identify")

    val all: List<SpellTemplate> = listOf(
        SpellTemplate(EMBER_BOLT, "Ember bolt", SpellKind.DAMAGE, mpCost = 2, damage = DamageDice(1, 6)),
        SpellTemplate(IDENTIFY, "Identify", SpellKind.IDENTIFY, mpCost = 1),
    )

    fun byId(id: SpellId): SpellTemplate? = all.firstOrNull { it.id == id }
}

/**
 * Combat numbers in one place, so a balance change is never hidden inside a rule.
 */
data class CombatRules(
    val baseDefense: Int = 10,
    /** Defend adds this to the player's defense until their next action. */
    val guardBonus: Int = 4,
    /** Used when the character carries no weapon with damage dice. */
    val unarmedDamage: DamageDice = DamageDice(1, 2),
    /** Defeat is bounded: at most this much gold, and the save is never deleted. */
    val defeatGoldLoss: Int = 10,
    /** Damage never drops below this when an attack lands. */
    val minimumDamage: Int = 1,
) {
    init {
        require(baseDefense > 0) { "base defense must be positive" }
        require(guardBonus >= 0) { "guard bonus cannot be negative" }
        require(defeatGoldLoss >= 0) { "defeat gold loss cannot be negative" }
        require(minimumDamage >= 1) { "minimum damage must be at least 1" }
    }
}

/**
 * Pure combat arithmetic. No campaign state, so defense, weapon choice and damage bounds are
 * unit tested directly instead of through a whole encounter.
 */
object CombatMath {

    /** Best armour in the pack wins; armour does not stack. */
    fun armorBonus(player: PlayerCharacter, catalog: ContentCatalog): Int =
        player.inventory.keys.mapNotNull { catalog.item(it) }.maxOfOrNull { it.armorBonus } ?: 0

    /** The heaviest weapon carried, or the unarmed dice. */
    fun weaponDice(player: PlayerCharacter, catalog: ContentCatalog, rules: CombatRules): DamageDice =
        player.inventory.keys
            .mapNotNull { catalog.item(it)?.damage }
            .maxByOrNull { it.count * it.sides + it.bonus }
            ?: rules.unarmedDamage

    fun playerDefense(player: PlayerCharacter, catalog: ContentCatalog, rules: CombatRules, guard: Int): Int =
        rules.baseDefense + player.attributes.modifier(Attribute.DEXTERITY) + armorBonus(player, catalog) + guard

    /** Roll damage dice plus a flat modifier, never below [minimumDamage]. */
    fun rollDamage(dice: DamageDice, modifier: Int, rng: RandomSource, minimumDamage: Int): Int {
        var total = dice.bonus + modifier
        repeat(dice.count) { total += Dice.roll(rng, dice.sides) }
        return maxOf(minimumDamage, total)
    }
}

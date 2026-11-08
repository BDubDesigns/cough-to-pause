package dev.harness.coughtopause.rpg

/**
 * The only source of randomness the rules may use. Injected everywhere so tests can script
 * exact dice and production can derive a reproducible stream per action.
 */
interface RandomSource {
    /** Uniform integer in `[0, bound)`. */
    fun nextInt(bound: Int): Int
}

/**
 * SplitMix64. Chosen over `java.util.Random`/`SplittableRandom` because its output is fully
 * specified by this file: the same seed produces the same dice on the JVM, on Android and after
 * any library upgrade, which is what makes rolls reproducible from a save.
 */
class SplitMix64(seed: Long) : RandomSource {
    private var state = seed

    fun nextLong(): Long {
        state += GOLDEN_GAMMA
        return mix64(state)
    }

    override fun nextInt(bound: Int): Int {
        require(bound > 0) { "bound must be positive" }
        val b = bound.toLong()
        // Rejection sampling over [0, 2^63) so every face is exactly equally likely.
        val limit = Long.MAX_VALUE - Long.MAX_VALUE % b
        while (true) {
            val r = nextLong() ushr 1
            if (r < limit) return (r % b).toInt()
        }
    }

    companion object {
        private val GOLDEN_GAMMA = 0x9E3779B97F4A7C15uL.toLong()

        internal fun mix64(input: Long): Long {
            var z = input
            z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9uL.toLong()
            z = (z xor (z ushr 27)) * 0x94D049BB133111EBuL.toLong()
            return z xor (z ushr 31)
        }
    }
}

object Seeds {
    /** Deterministically combines seed material (campaign seed, action ordinal, action id hash). */
    fun combine(vararg parts: Long): Long {
        var h = 0x6A09E667F3BCC909uL.toLong()
        for (p in parts) h = SplitMix64.mix64(h xor p)
        return h
    }

    /** FNV-1a 64 over UTF-8. Stable across platforms, unlike relying on hash-map internals. */
    fun stableHash(text: String): Long {
        var h = 0xCBF29CE484222325uL.toLong()
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            h = h xor (byte.toLong() and 0xFF)
            h *= 0x100000001B3L
        }
        return h
    }
}

object Dice {
    /** One die with [sides] faces, 1..sides. */
    fun roll(rng: RandomSource, sides: Int): Int {
        require(sides >= 2) { "a die needs at least two sides" }
        return rng.nextInt(sides) + 1
    }
}

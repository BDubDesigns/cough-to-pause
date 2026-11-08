package dev.harness.coughtopause.rpg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiceTest {

    @Test
    fun splitMix64MatchesReferenceOutput() {
        // Reference SplitMix64 (Steele/Lea/Vigna) from state 0. Pins the algorithm so saved
        // seeds keep producing the same dice across platforms and refactors.
        val rng = SplitMix64(0L)
        assertEquals(0xE220A8397B1DCDAFuL.toLong(), rng.nextLong())
        assertEquals(0x6E789E6AA1B965F4uL.toLong(), rng.nextLong())
    }

    @Test
    fun sameSeedProducesSameRolls() {
        val a = SplitMix64(1234L)
        val b = SplitMix64(1234L)
        val rollsA = List(200) { Dice.roll(a, 20) }
        val rollsB = List(200) { Dice.roll(b, 20) }
        assertEquals(rollsA, rollsB)
        assertNotEquals(rollsA, List(200) { Dice.roll(SplitMix64(1235L), 20) })
    }

    @Test
    fun d20CoversEveryFaceAndNothingElse() {
        val rng = SplitMix64(7L)
        val counts = IntArray(21)
        repeat(20_000) { counts[Dice.roll(rng, 20)]++ }
        assertEquals(0, counts[0])
        (1..20).forEach { face -> assertTrue("face $face count ${counts[face]}", counts[face] in 800..1200) }
    }

    @Test
    fun seedDerivationIsStable() {
        assertEquals(Seeds.combine(1L, 2L, 3L), Seeds.combine(1L, 2L, 3L))
        assertNotEquals(Seeds.combine(1L, 2L, 3L), Seeds.combine(1L, 3L, 2L))
        assertEquals(0xCBF29CE484222325uL.toLong(), Seeds.stableHash(""))
        assertEquals(0xAF63DC4C8601EC8CuL.toLong(), Seeds.stableHash("a"))
    }
}

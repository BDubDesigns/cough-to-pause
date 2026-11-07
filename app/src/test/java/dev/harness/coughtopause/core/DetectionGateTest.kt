package dev.harness.coughtopause.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionGateTest {

    private var settings = DetectorSettings(cooldownMs = 1000, confirmationWindows = 1)
    private fun gate() = CoughGate { settings }

    @Test
    fun acceptsAboveThreshold() {
        val g = gate()
        val result = g.offer(0.8f, 10_000, 0.55f)
        assertTrue(result is GateResult.Accepted)
    }

    @Test
    fun rejectsBelowThreshold() {
        val g = gate()
        val result = g.offer(0.2f, 10_000, 0.55f)
        assertTrue(result is GateResult.RejectedLowScore)
    }

    @Test
    fun cooldownSuppressesTheTailOfOneCough() {
        val g = gate()
        assertTrue(g.offer(0.8f, 10_000, 0.55f) is GateResult.Accepted)
        val suppressed = g.offer(0.9f, 10_100, 0.55f)
        assertTrue(suppressed is GateResult.RejectedCooldown)
        assertEquals(100L, (suppressed as GateResult.RejectedCooldown).suppressedForMs)
    }

    @Test
    fun acceptsAgainAfterCooldown() {
        val g = gate()
        assertTrue(g.offer(0.8f, 10_000, 0.55f) is GateResult.Accepted)
        assertTrue(g.offer(0.8f, 11_100, 0.55f) is GateResult.Accepted)
    }

    @Test
    fun confirmationWindowsRequireConsecutiveAgreement() {
        settings = settings.copy(confirmationWindows = 2)
        val g = gate()
        val first = g.offer(0.8f, 10_000, 0.5f)
        assertFalse("one window is not confirmation", first is GateResult.Accepted)
        val second = g.offer(0.8f, 10_250, 0.5f)
        assertTrue(second is GateResult.Accepted)
    }

    @Test
    fun belowThresholdResetsTheConfirmationChain() {
        settings = settings.copy(confirmationWindows = 2)
        val g = gate()
        g.offer(0.8f, 10_000, 0.5f)
        g.offer(0.1f, 10_250, 0.5f)
        val after = g.offer(0.8f, 10_500, 0.5f)
        assertFalse(after is GateResult.Accepted)
    }

    @Test
    fun sensitivityMovesTheEffectiveThreshold() {
        val strict = DetectorSettings(sensitivity = 0f)
        val loose = DetectorSettings(sensitivity = 1f)
        assertTrue(
            strict.effectiveDspThreshold() > loose.effectiveDspThreshold(),
        )
        assertTrue(loose.effectiveDspThreshold() >= 0.05f)
        assertTrue(strict.effectiveDspThreshold() <= 0.99f)
    }

    @Test
    fun calibrationGainLowersTheThreshold() {
        val base = DetectorSettings(dspThreshold = 0.5f, calibrateGain = 0f)
        val raised = base.copy(calibrateGain = 0.1f)
        assertTrue(raised.effectiveDspThreshold() < base.effectiveDspThreshold())
    }

    @Test
    fun noiseFloorFollowsQuietAndIgnoresLoudTransients() {
        val tracker = NoiseFloorTracker(initialDb = -60f)
        repeat(20) { tracker.update(-62f) }
        val quietFloor = tracker.floorDb
        // A single loud window must not drag the floor up to the burst level.
        tracker.update(-10f)
        assertTrue("floor stays near the room", tracker.floorDb < quietFloor + 3f)
        repeat(100) { tracker.update(-70f) }
        assertTrue("floor follows a quieter room", tracker.floorDb < quietFloor)
    }

    @Test
    fun snrIsRelativeToTheTrackedFloor() {
        val tracker = NoiseFloorTracker(initialDb = -50f)
        assertEquals(20f, tracker.snrDb(-30f), 0.001f)
    }
}

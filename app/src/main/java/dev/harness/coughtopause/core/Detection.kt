package dev.harness.coughtopause.core

/** Which classifier produced a decision. Kept per event so the diagnostics screen can compare. */
enum class DetectorBackend {
    /** Hand-written DSP transient detector: always available, no model download. */
    DSP,

    /** YAMNet (AudioSet) through TensorFlow Lite on the device. */
    YAMNET,

    /** DSP onset gate, confirmed by YAMNet. Lowest false-positive rate in the experiment. */
    HYBRID,
}

/**
 * Everything the detector can be tuned with. Persisted, so a calibration survives restarts.
 *
 * [sensitivity] is an operator control in 0..1: 0 ignores quiet coughs, 1 accepts almost any
 * transient. It shifts the effective threshold around the calibrated base threshold.
 */
data class DetectorSettings(
    val backend: DetectorBackend = DetectorBackend.HYBRID,
    val sensitivity: Float = 0.5f,
    val dspThreshold: Float = 0.55f,
    val yamnetThreshold: Float = 0.32f,
    val cooldownMs: Int = 1200,
    val windowMs: Int = 500,
    val hopMs: Int = 250,
    val minPeakDb: Float = -46f,
    val minSnrDb: Float = 9f,
    val confirmationWindows: Int = 1,
    val calibrateGain: Float = 0f,
) {
    fun effectiveDspThreshold(): Float {
        // sensitivity 0 -> 1.25x base (stricter), sensitivity 1 -> 0.75x base (looser)
        val scaled = dspThreshold * (1.25f - 0.5f * sensitivity.coerceIn(0f, 1f))
        return (scaled - calibrateGain).coerceIn(0.05f, 0.99f)
    }

    fun effectiveYamnetThreshold(): Float {
        val scaled = yamnetThreshold * (1.35f - 0.7f * sensitivity.coerceIn(0f, 1f))
        return (scaled - calibrateGain).coerceIn(0.05f, 0.99f)
    }
}

/** Noise floor estimate in dBFS, tracking the quiet end of the room. */
class NoiseFloorTracker(
    initialDb: Float = -62f,
    private val fallRate: Float = 0.35f,
    private val riseRate: Float = 0.02f,
) {
    var floorDb: Float = initialDb
        private set

    fun update(rmsDb: Float) {
        if (!rmsDb.isFinite()) return
        val rate = if (rmsDb < floorDb) fallRate else riseRate
        floorDb += (rmsDb - floorDb) * rate
        if (floorDb > -20f) floorDb = -20f
        if (floorDb < -90f) floorDb = -90f
    }

    fun snrDb(rmsDb: Float): Float = rmsDb - floorDb
}

/** Outcome of offering one window score to the gate. */
sealed interface GateResult {
    data class Accepted(val timestampMs: Long, val score: Float) : GateResult
    data class RejectedLowScore(val score: Float, val threshold: Float) : GateResult
    data class RejectedCooldown(val suppressedForMs: Long) : GateResult
}

/**
 * Cooldown + confirmation gate in front of the state machine.
 *
 * - A score below the threshold is dropped.
 * - Repeated detections inside [DetectorSettings.cooldownMs] are dropped, so one cough produces
 *   one pause even though a cough straddles several analysis windows.
 * - [DetectorSettings.confirmationWindows] > 1 requires consecutive windows to agree.
 */
class CoughGate(
    private val settings: () -> DetectorSettings,
) {
    private var lastAcceptedMs = Long.MIN_VALUE
    private var consecutive = 0

    fun reset() {
        consecutive = 0
        lastAcceptedMs = Long.MIN_VALUE
    }

    fun offer(score: Float, timestampMs: Long, threshold: Float): GateResult {
        val s = settings()
        if (score < threshold) {
            consecutive = 0
            return GateResult.RejectedLowScore(score, threshold)
        }
        val sinceLast = timestampMs - lastAcceptedMs
        if (lastAcceptedMs != Long.MIN_VALUE && sinceLast < s.cooldownMs) {
            return GateResult.RejectedCooldown(sinceLast)
        }
        consecutive += 1
        if (consecutive < s.confirmationWindows.coerceAtLeast(1)) {
            return GateResult.RejectedLowScore(score, threshold)
        }
        consecutive = 0
        lastAcceptedMs = timestampMs
        return GateResult.Accepted(timestampMs, score)
    }
}

/** One detection as reported to the service and the diagnostics screen. */
data class DetectionEvent(
    val timestampMs: Long,
    val score: Float,
    val backend: DetectorBackend,
    val peakDb: Float,
    val snrDb: Float,
    val detail: String,
)

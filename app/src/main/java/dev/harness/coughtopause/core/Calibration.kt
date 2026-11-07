package dev.harness.coughtopause.core

/**
 * Result of a calibration pass. Stored so the diagnostics screen can show how the threshold
 * was obtained and how separable the two classes actually were.
 */
data class CalibrationResult(
    val threshold: Float,
    val positives: Int,
    val negatives: Int,
    val truePositiveRate: Float,
    val falsePositiveRate: Float,
    val margin: Float,
    val createdAtEpochMs: Long,
)

/**
 * Threshold selection from labelled scores captured on this device with this microphone.
 *
 * The rule is deliberately explicit: pick the *lowest* threshold that keeps the false-positive
 * rate at or below [targetFpr], which maximises recall subject to the operator's tolerance.
 * With fewer than two negatives there is no evidence about false positives, so the threshold
 * falls back to the midpoint between the classes.
 */
object Calibrator {

    fun compute(
        positiveScores: List<Float>,
        negativeScores: List<Float>,
        targetFpr: Float = 0.05f,
        createdAtEpochMs: Long = 0L,
    ): CalibrationResult {
        val pos = positiveScores.filter { it.isFinite() }.sorted()
        val neg = negativeScores.filter { it.isFinite() }.sorted()
        if (pos.isEmpty() || neg.isEmpty()) {
            val threshold = if (pos.isNotEmpty()) {
                (pos.first() * 0.85f).coerceIn(0.05f, 0.95f)
            } else {
                0.55f
            }
            return CalibrationResult(threshold, pos.size, neg.size, 0f, 0f, 0f, createdAtEpochMs)
        }

        val candidates = (pos + neg).map { it.coerceIn(0f, 1f) }.distinct().sorted()
        var chosen: Float? = null
        for (t in candidates) {
            val fpr = neg.count { it >= t }.toFloat() / neg.size
            if (fpr <= targetFpr) {
                chosen = t
                break
            }
        }
        val threshold = chosen ?: (neg.max() + 0.02f).coerceAtMost(0.99f)
        val tpr = pos.count { it >= threshold }.toFloat() / pos.size
        val fpr = neg.count { it >= threshold }.toFloat() / neg.size
        val margin = if (pos.isNotEmpty() && neg.isNotEmpty()) pos.first() - neg.last() else 0f
        return CalibrationResult(
            threshold = threshold.coerceIn(0.05f, 0.99f),
            positives = pos.size,
            negatives = neg.size,
            truePositiveRate = tpr,
            falsePositiveRate = fpr,
            margin = margin,
            createdAtEpochMs = createdAtEpochMs,
        )
    }
}

/**
 * Detection quality counters. These are the numbers the playtest report quotes: they are
 * operator-entered truth (a cough the detector missed, a pause nobody coughed for), not
 * self-assessment by the detector.
 */
class DetectionMetrics {

    private var coughPauses = 0
    private var falsePositives = 0
    private var missedCoughs = 0
    private var cooldownSuppressed = 0
    private var lowScoreRejections = 0
    private val latencies = ArrayDeque<Long>()

    @Synchronized
    fun onCoughPause(latencyMs: Long?) {
        coughPauses++
        if (latencyMs != null && latencyMs >= 0) {
            latencies.addLast(latencyMs)
            while (latencies.size > MAX_LATENCIES) latencies.removeFirst()
        }
    }

    @Synchronized
    fun onFalsePositive() {
        falsePositives++
    }

    @Synchronized
    fun onMissedCough() {
        missedCoughs++
    }

    @Synchronized
    fun onGateRejected(result: GateResult) {
        when (result) {
            is GateResult.RejectedCooldown -> cooldownSuppressed++
            is GateResult.RejectedLowScore -> lowScoreRejections++
            is GateResult.Accepted -> Unit
        }
    }

    @Synchronized
    fun reset() {
        coughPauses = 0
        falsePositives = 0
        missedCoughs = 0
        cooldownSuppressed = 0
        lowScoreRejections = 0
        latencies.clear()
    }

    @Synchronized
    fun snapshot(): MetricsSnapshot = MetricsSnapshot(
        coughPauses = coughPauses,
        falsePositives = falsePositives,
        missedCoughs = missedCoughs,
        cooldownSuppressed = cooldownSuppressed,
        lowScoreRejections = lowScoreRejections,
        latency = latencyStats(),
    )

    private fun latencyStats(): LatencyStats {
        if (latencies.isEmpty()) return LatencyStats(0, 0f, 0L, 0L, 0L)
        val sorted = latencies.sorted()
        val avg = sorted.average().toFloat()
        val p95Index = ((sorted.size - 1) * 0.95f).toInt().coerceIn(0, sorted.size - 1)
        return LatencyStats(
            count = sorted.size,
            avgMs = avg,
            maxMs = sorted.last(),
            p95Ms = sorted[p95Index],
            minMs = sorted.first(),
        )
    }

    private companion object {
        const val MAX_LATENCIES = 200
    }
}

data class LatencyStats(
    val count: Int,
    val avgMs: Float,
    val maxMs: Long,
    val p95Ms: Long,
    val minMs: Long,
)

data class MetricsSnapshot(
    val coughPauses: Int,
    val falsePositives: Int,
    val missedCoughs: Int,
    val cooldownSuppressed: Int,
    val lowScoreRejections: Int,
    val latency: LatencyStats,
) {
    /** Operator-labelled precision: 1.0 means every pause was a real cough. */
    val precision: Float
        get() = if (coughPauses + falsePositives == 0) 0f else coughPauses.toFloat() / (coughPauses + falsePositives)

    /** Recall against operator-observed coughs. */
    val recall: Float
        get() = if (coughPauses + missedCoughs == 0) 0f else coughPauses.toFloat() / (coughPauses + missedCoughs)
}

package dev.harness.coughtopause.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** In-place iterative radix-2 FFT. Small and allocation free, good enough for 32 ms frames. */
internal object Fft {

    fun transform(re: FloatArray, im: FloatArray) {
        val n = re.size
        require(n > 1 && (n and (n - 1)) == 0) { "FFT size must be a power of two" }
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val angle = -2.0 * PI / len
            val wRe = cos(angle).toFloat()
            val wIm = sin(angle).toFloat()
            var i = 0
            while (i < n) {
                var curRe = 1f
                var curIm = 0f
                val half = len shr 1
                for (k in 0 until half) {
                    val aRe = re[i + k]
                    val aIm = im[i + k]
                    val bRe = re[i + k + half] * curRe - im[i + k + half] * curIm
                    val bIm = re[i + k + half] * curIm + im[i + k + half] * curRe
                    re[i + k] = aRe + bRe
                    im[i + k] = aIm + bIm
                    re[i + k + half] = aRe - bRe
                    im[i + k + half] = aIm - bIm
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
                i += len
            }
            len = len shl 1
        }
    }
}

/** Feature vector of a single analysis window. All fields are diagnostics-friendly. */
data class DspResult(
    val score: Float,
    val peakDb: Float,
    val floorDb: Float,
    val snrDb: Float,
    val onsetMs: Float,
    val decayMs: Float,
    val activeMs: Float,
    val highBandRatio: Float,
    val flatness: Float,
    val zcr: Float,
    val centroidHz: Float,
)

/**
 * Transient ("cough-shaped") scorer.
 *
 * A cough, as observed through a phone microphone, is a broadband burst with a hard onset,
 * a fast decay, and substantial energy above 2 kHz. Voiced speech is the opposite: energy in the
 * low band, strongly periodic, gradual envelope. This scorer is deliberately small so the
 * *measurements* decide whether it is good enough, rather than the model's reputation.
 */
class DspCoughScorer(
    private val sampleRateHz: Int = 16_000,
    private val frameSize: Int = 512,
    private val hopSize: Int = 160,
) {

    private val frameMs = 1000f * hopSize / sampleRateHz

    fun analyze(window: FloatArray, floorDbIn: Float): DspResult {
        if (window.size < frameSize) {
            return DspResult(0f, -120f, floorDbIn, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        }
        val frameCount = 1 + (window.size - frameSize) / hopSize
        val rms = FloatArray(frameCount)
        val zcrs = FloatArray(frameCount)
        for (f in 0 until frameCount) {
            var sum = 0.0
            var crossings = 0
            var prev = window[f * hopSize]
            for (i in 0 until frameSize) {
                val v = window[f * hopSize + i]
                sum += v.toDouble() * v
                if ((v >= 0f) != (prev >= 0f)) crossings++
                prev = v
            }
            rms[f] = sqrt(sum / frameSize).toFloat()
            zcrs[f] = crossings.toFloat() / frameSize
        }

        var peakFrame = 0
        var peakRms = 0f
        for (f in 0 until frameCount) {
            if (rms[f] > peakRms) {
                peakRms = rms[f]
                peakFrame = f
            }
        }
        val peakDb = if (peakRms <= 1e-7f) -120f else 20f * log10(peakRms)
        val floorDb = if (floorDbIn.isFinite()) floorDbIn else -62f
        val snrDb = peakDb - floorDb

        val threshold = max(peakRms * 0.35f, 1e-6f)
        var startFrame = peakFrame
        while (startFrame > 0 && rms[startFrame - 1] > threshold) startFrame--
        var endFrame = peakFrame
        while (endFrame < frameCount - 1 && rms[endFrame + 1] > threshold) endFrame++

        val onsetMs = (peakFrame - startFrame) * frameMs
        val decayMs = (endFrame - peakFrame) * frameMs
        val activeMs = (endFrame - startFrame + 1) * frameMs

        val spectrum = magnitudeSpectrum(window, peakFrame)
        val binHz = sampleRateHz.toFloat() / frameSize
        var lowEnergy = 0.0
        var midEnergy = 0.0
        var highEnergy = 0.0
        var totalEnergy = 0.0
        var logSum = 0.0
        var linearSum = 0.0
        var flatBins = 0
        var weighted = 0.0
        for (bin in 2 until frameSize / 2) {
            val hz = bin * binHz
            val magnitude = spectrum[bin]
            val power = (magnitude * magnitude).toDouble()
            totalEnergy += power
            weighted += hz * magnitude
            if (hz < 500f) lowEnergy += power else if (hz < 2000f) midEnergy += power else highEnergy += power
            if (hz in 150f..6000f) {
                logSum += ln(magnitude + 1e-9f)
                linearSum += magnitude
                flatBins++
            }
        }
        val highRatio = if (totalEnergy > 1e-12) (highEnergy / totalEnergy).toFloat() else 0f
        val flatness = if (flatBins > 0 && linearSum > 1e-9) {
            (exp(logSum / flatBins) / (linearSum / flatBins)).toFloat()
        } else 0f
        val centroid = if (linearSum > 1e-9) (weighted / linearSum).toFloat() else 0f

        // ZCR measured over the frames that actually contain the burst, not the whole window.
        var zcrAcc = 0f
        for (f in startFrame..endFrame) zcrAcc += zcrs[f]
        val zcr = if (endFrame >= startFrame) zcrAcc / (endFrame - startFrame + 1) else 0f

        val snrTerm = clamp01((snrDb - 6f) / 24f)
        val attackTerm = clamp01((110f - onsetMs) / 90f)
        val highTerm = clamp01((highRatio - 0.10f) / 0.25f)
        val flatTerm = clamp01((flatness - 0.12f) / 0.33f)
        val zcrTerm = clamp01((zcr - 0.06f) / 0.20f)

        var score = 0.34f * snrTerm + 0.22f * attackTerm + 0.20f * highTerm +
            0.12f * flatTerm + 0.12f * zcrTerm

        // Voiced, sustained material (speech, humming) shapes down hard even when loud.
        if (zcr < 0.06f && highRatio < 0.18f) score *= 0.72f
        // Long noisy events are more likely room noise than a cough.
        if (activeMs > 420f) score *= 0.70f

        return DspResult(
            score = score.coerceIn(0f, 1f),
            peakDb = peakDb,
            floorDb = floorDb,
            snrDb = snrDb,
            onsetMs = onsetMs,
            decayMs = decayMs,
            activeMs = activeMs,
            highBandRatio = highRatio,
            flatness = flatness,
            zcr = zcr,
            centroidHz = centroid,
        )
    }

    private fun magnitudeSpectrum(window: FloatArray, frame: Int): FloatArray {
        val re = FloatArray(frameSize)
        val im = FloatArray(frameSize)
        val offset = frame * hopSize
        for (i in 0 until frameSize) {
            val idx = offset + i
            val sample = if (idx < window.size) window[idx] else 0f
            // Hann window keeps leakage out of the high-band estimate.
            val w = (0.5 - 0.5 * cos(2.0 * PI * i / (frameSize - 1))).toFloat()
            re[i] = sample * w
        }
        Fft.transform(re, im)
        val mag = FloatArray(frameSize / 2)
        for (i in mag.indices) mag[i] = sqrt(re[i] * re[i] + im[i] * im[i])
        return mag
    }

    private fun clamp01(v: Float): Float = if (v < 0f) 0f else if (v > 1f) 1f else v
}

/** RMS of a float window in dBFS, used for the noise floor and the "is anything happening" gate. */
fun rmsDb(window: FloatArray): Float {
    if (window.isEmpty()) return -120f
    var sum = 0.0
    for (v in window) sum += v.toDouble() * v
    val rms = sqrt(sum / window.size)
    return if (rms <= 1e-7) -120f else 20f * log10(rms.toFloat())
}

/** Peak absolute sample value of a window, used to detect clipping and silence. */
fun peakAbs(window: FloatArray): Float {
    var peak = 0f
    for (v in window) {
        val a = abs(v)
        if (a > peak) peak = a
    }
    return peak
}

package dev.harness.coughtopause.core

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * DSP tests with synthetic signals whose shape is known.
 *
 * These do not prove real-world accuracy — only that the scorer separates a broadband transient
 * from voiced speech, silence and steady room noise, and that it keeps doing so for different
 * microphone gains. Real accuracy is measured on hardware and reported separately.
 */
class DspScorerTest {

    private val sampleRate = 16_000
    private val scorer = DspCoughScorer(sampleRate)

    private fun window(seconds: Float = 0.5f) = FloatArray((sampleRate * seconds).toInt())

    private fun addCough(target: FloatArray, startSec: Float, durationSec: Float, amplitude: Float) {
        val start = (startSec * sampleRate).toInt()
        val length = (durationSec * sampleRate).toInt()
        val random = Random(1234)
        for (i in 0 until length) {
            val idx = start + i
            if (idx !in target.indices) break
            val decay = exp(-4.0 * i / length).toFloat()
            val attack = if (i < 80) i / 80f else 1f
            target[idx] += (random.nextFloat() * 2f - 1f) * amplitude * decay * attack
        }
    }

    private fun addSpeech(target: FloatArray, startSec: Float, durationSec: Float, amplitude: Float) {
        val start = (startSec * sampleRate).toInt()
        val length = (durationSec * sampleRate).toInt()
        for (i in 0 until length) {
            val idx = start + i
            if (idx !in target.indices) break
            val t = i / sampleRate.toFloat()
            val envelope = sin(PI * (i.toFloat() / length)).toFloat().coerceAtLeast(0f)
            val f0 = 140f * (1f + 0.05f * sin(2 * PI * 3 * t).toFloat())
            val sample = 0.6f * sin(2 * PI * f0 * t).toFloat() +
                0.3f * sin(2 * PI * 2 * f0 * t).toFloat() +
                0.1f * sin(2 * PI * 3 * f0 * t).toFloat()
            target[idx] += sample * envelope * amplitude
        }
    }

    private fun addRoomNoise(target: FloatArray, amplitude: Float, seed: Int = 7) {
        val random = Random(seed)
        for (i in target.indices) {
            target[i] += (random.nextFloat() * 2f - 1f) * amplitude
        }
    }

    @Test
    fun syntheticCoughScoresHigh() {
        val w = window()
        addRoomNoise(w, 0.002f)
        addCough(w, 0.15f, 0.14f, 0.8f)
        val result = scorer.analyze(w, -62f)
        assertTrue("cough score ${result.score} should clear 0.55", result.score > 0.55f)
        assertTrue("expected broadband high band, got ${result.highBandRatio}", result.highBandRatio > 0.2f)
        assertTrue("expected a sharp onset, got ${result.onsetMs} ms", result.onsetMs <= 120f)
    }

    @Test
    fun voicedSpeechScoresLow() {
        val w = window()
        addRoomNoise(w, 0.002f)
        addSpeech(w, 0.05f, 0.4f, 0.35f)
        val result = scorer.analyze(w, -62f)
        assertTrue("speech score ${result.score} should stay under 0.55", result.score < 0.55f)
        assertTrue("speech is low band", result.highBandRatio < 0.35f)
    }

    @Test
    fun coughOutranksSpeechOnTheSameFloor() {
        val coughWindow = window().also { addCough(it, 0.15f, 0.14f, 0.8f) }
        val speechWindow = window().also { addSpeech(it, 0.05f, 0.4f, 0.35f) }
        val cough = scorer.analyze(coughWindow, -62f)
        val speech = scorer.analyze(speechWindow, -62f)
        assertTrue("cough ${cough.score} > speech ${speech.score}", cough.score > speech.score + 0.15f)
    }

    @Test
    fun silenceAndSteadyNoiseStayBelowTheGate() {
        val silence = scorer.analyze(window(), -62f)
        assertTrue("silence score ${silence.score}", silence.score < 0.2f)

        val noise = window().also { addRoomNoise(it, 0.01f) }
        val noiseResult = scorer.analyze(noise, -42f)
        assertTrue("steady noise score ${noiseResult.score}", noiseResult.score < 0.55f)
    }

    @Test
    fun quieterCoughStillOutranksTheRoom() {
        val w = window()
        addRoomNoise(w, 0.003f)
        addCough(w, 0.2f, 0.12f, 0.25f)
        val result = scorer.analyze(w, -55f)
        assertTrue("quiet cough score ${result.score}", result.score > 0.4f)
        assertTrue(result.snrDb > 6f)
    }

    @Test
    fun rmsAndPeakAreSane() {
        val w = FloatArray(1600) { 0.5f }
        assertTrue(rmsDb(w) > -10f && rmsDb(w) < 0f)
        assertTrue(rmsDb(FloatArray(1600)) < -100f)
        assertTrue(peakAbs(w) == 0.5f)
    }

    @Test
    fun tooShortWindowIsHandledGracefully() {
        val result = scorer.analyze(FloatArray(64), -60f)
        assertTrue(result.score == 0f)
    }
}

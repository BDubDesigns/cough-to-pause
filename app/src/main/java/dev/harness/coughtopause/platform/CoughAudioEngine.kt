package dev.harness.coughtopause.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.AcousticEchoCanceler
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import dev.harness.coughtopause.core.CoughGate
import dev.harness.coughtopause.core.DetectionEvent
import dev.harness.coughtopause.core.DetectorBackend
import dev.harness.coughtopause.core.DetectorSettings
import dev.harness.coughtopause.core.DspCoughScorer
import dev.harness.coughtopause.core.DspResult
import dev.harness.coughtopause.core.GateResult
import dev.harness.coughtopause.core.NoiseFloorTracker
import dev.harness.coughtopause.core.rmsDb
import kotlin.math.min

/**
 * Microphone → window → detector pipeline.
 *
 * Design notes that matter for the experiment:
 *  - Fixed sample rate (16 kHz) and fixed window/hop so latency numbers are comparable.
 *  - Windows overlap: a cough straddles several windows, which is exactly why the gate has a
 *    cooldown instead of the detector trying to be precise to the millisecond.
 *  - No audio is ever written to disk. Windows live in one ring buffer and are recycled.
 */
class CoughAudioEngine(
    private val context: Context,
    private val settingsProvider: () -> DetectorSettings,
    private val onStatus: (String) -> Unit,
    private val onDetection: (DetectionEvent) -> Unit,
    private val onAnalysis: (DspResult, Float, Boolean) -> Unit,
    private val onGateRejected: (GateResult) -> Unit,
) {

    private val scorer = DspCoughScorer(SAMPLE_RATE)
    private val floor = NoiseFloorTracker()
    private val gate = CoughGate(settingsProvider)

    private var record: AudioRecord? = null
    private var yamnet: YamnetClassifier? = null
    private var thread: Thread? = null
    private var echoCanceller: AcousticEchoCanceler? = null

    @Volatile
    private var running = false

    @Volatile
    var aecRequested: Boolean = false
        private set

    @Volatile
    var aecActive: Boolean = false
        private set

    @Volatile
    var capturedSampleRate: Int = SAMPLE_RATE
        private set

    @Volatile
    var lastYamnetScore: Float = 0f
        private set

    @Volatile
    var lastDspScore: Float = 0f
        private set

    @Volatile
    var modelStatus: String = "not loaded"
        private set

    private var lastModelReportMs = 0L

    private val ring = FloatArray(YAMNET_WINDOW)
    private var ringPos = 0
    private var ringFilled = 0

    fun start(): Boolean {
        if (running) return true
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            onStatus("microphone permission not granted")
            return false
        }
        val settings = settingsProvider()
        if (settings.backend != DetectorBackend.DSP) {
            yamnet = YamnetClassifier(context).also { it.load() }
            modelStatus = yamnet?.status ?: "not loaded"
            HarnessState.update { it.copy(modelStatus = modelStatus) }
        } else {
            modelStatus = "DSP only (no model loaded)"
            HarnessState.update { it.copy(modelStatus = modelStatus) }
        }

        val rec = createRecord()
        if (rec == null) {
            onStatus("AudioRecord could not be initialised")
            return false
        }
        capturedSampleRate = SAMPLE_RATE
        record = rec

        aecRequested = AcousticEchoCanceler.isAvailable()
        aecActive = false
        if (aecRequested) {
            echoCanceller = AcousticEchoCanceler.create(rec.audioSessionId)?.also { aec ->
                aec.enabled = true
                aecActive = aec.enabled
            }
        }
        running = true
        thread = Thread({ loop(rec) }, "cough-audio").apply {
            priority = Thread.MAX_PRIORITY - 2
            start()
        }
        onStatus("listening @ ${SAMPLE_RATE} Hz (aec=${if (aecActive) "on" else "off"})")
        return true
    }

    fun stop() {
        running = false
        thread?.join(400)
        thread = null
        echoCanceller?.let { runCatching { it.enabled = false }; it.release() }
        echoCanceller = null
        aecActive = false
        record?.let { rec ->
            runCatching { rec.stop() }
            runCatching { rec.release() }
        }
        record = null
        gate.reset()
        floor.let { } // floor intentionally keeps its estimate across brief restarts
        onStatus("stopped")
    }

    fun resetGate() = gate.reset()

    private fun createRecord(): AudioRecord? {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = if (minBuffer > 0) minBuffer * 3 else SAMPLE_RATE
        val sources = intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)
        for (source in sources) {
            val rec = try {
                AudioRecord(
                    source,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize,
                )
            } catch (e: Throwable) {
                null
            }
            if (rec != null && rec.state == AudioRecord.STATE_INITIALIZED) return rec
            runCatching { rec?.release() }
        }
        return null
    }

    private fun loop(rec: AudioRecord) {
        val chunk = ShortArray(1_200) // 75 ms
        val floats = FloatArray(chunk.size)
        var samplesSinceAnalysis = 0
        try {
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                onStatus("AudioRecord did not start (another app may hold the microphone)")
                running = false
                return
            }
            while (running) {
                val read = rec.read(chunk, 0, chunk.size)
                if (read <= 0) {
                    if (read < 0) {
                        onStatus("microphone read error $read")
                        break
                    }
                    continue
                }
                for (i in 0 until read) floats[i] = chunk[i] / 32768f
                push(floats, read)
                samplesSinceAnalysis += read
                val hop = settingsProvider().hopMs.coerceIn(120, 900) * SAMPLE_RATE / 1000
                if (samplesSinceAnalysis >= hop) {
                    samplesSinceAnalysis = 0
                    analyse()
                }
            }
        } catch (e: Throwable) {
            onStatus("audio loop failed: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            running = false
        }
    }

    private fun push(samples: FloatArray, count: Int) {
        for (i in 0 until count) {
            ring[ringPos] = samples[i]
            ringPos = (ringPos + 1) % ring.size
            if (ringFilled < ring.size) ringFilled++
        }
    }

    private fun tail(count: Int): FloatArray {
        val n = min(count, ringFilled)
        val out = FloatArray(n)
        var idx = (ringPos - n + ring.size) % ring.size
        for (i in 0 until n) {
            out[i] = ring[idx]
            idx = (idx + 1) % ring.size
        }
        return out
    }

    private fun analyse() {
        val settings = settingsProvider()
        val windowSamples = settings.windowMs.coerceIn(250, 1500) * SAMPLE_RATE / 1000
        if (ringFilled < windowSamples / 2) return
        val window = tail(windowSamples)
        val dsp = scorer.analyze(window, floor.floorDb)
        floor.update(rmsDb(window))
        lastDspScore = dsp.score

        val yamnetResult = yamnet?.let { if (it.loaded) it.classify(tail(it.inputSampleCount)) else null }
        lastYamnetScore = yamnetResult?.combined ?: 0f
        val nowMs = SystemClock.elapsedRealtime()
        if (yamnet?.loaded == true && nowMs - lastModelReportMs > 2_000) {
            lastModelReportMs = nowMs
            val model = yamnet
            HarnessState.update {
                it.copy(modelStatus = "${model?.status ?: ""} · inference ${model?.lastInferenceMs} ms")
            }
        }

        val threshold: Float
        val score: Float
        when (settings.backend) {
            DetectorBackend.DSP -> {
                threshold = settings.effectiveDspThreshold()
                score = dsp.score
            }
            DetectorBackend.YAMNET -> {
                threshold = settings.effectiveYamnetThreshold()
                score = yamnetResult?.combined ?: 0f
            }
            DetectorBackend.HYBRID -> {
                if (yamnetResult == null) {
                    threshold = settings.effectiveDspThreshold()
                    score = dsp.score
                } else {
                    // DSP is the fast gate, YAMNet is the confirmer.
                    threshold = settings.effectiveYamnetThreshold()
                    score = if (dsp.score >= settings.effectiveDspThreshold()) yamnetResult.combined else 0f
                }
            }
        }

        val silent = dsp.peakDb < settings.minPeakDb || dsp.snrDb < settings.minSnrDb
        val gated = if (silent) 0f else score
        val timestamp = SystemClock.elapsedRealtime()
        val result = gate.offer(gated, timestamp, threshold)

        val accepted = result is GateResult.Accepted
        onAnalysis(dsp, lastYamnetScore, accepted)
        when (result) {
            is GateResult.Accepted -> {
                val detail = buildString {
                    append("dsp=%.2f yamnet=%.2f peak=%.0fdB snr=%.0fdB onset=%.0fms high=%.2f flat=%.2f zcr=%.2f"
                        .format(
                            dsp.score, lastYamnetScore, dsp.peakDb, dsp.snrDb,
                            dsp.onsetMs, dsp.highBandRatio, dsp.flatness, dsp.zcr,
                        ))
                }
                onDetection(
                    DetectionEvent(
                        timestampMs = result.timestampMs,
                        score = result.score,
                        backend = settings.backend,
                        peakDb = dsp.peakDb,
                        snrDb = dsp.snrDb,
                        detail = detail,
                    ),
                )
            }
            else -> onGateRejected(result)
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val YAMNET_WINDOW = 16_000
    }
}

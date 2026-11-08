package dev.harness.coughtopause.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistenceTest {

    @Test
    fun sessionRoundTripsThroughJson() {
        val session = PersistedSession(
            scriptId = NarrationScript.SCRIPT_ID,
            status = PlaybackStatus.PAUSED,
            lineIndex = 4,
            lineId = "L05",
            reason = PauseReason.COUGH,
            pausedAtMs = 123_456L,
            counters = Counters(coughPauses = 3, resumes = 2, ignoredCoughs = 1, completedLines = 4, staleCallbacks = 2),
            updatedAtEpochMs = 1_700_000_000_000L,
        )
        val decoded = SessionCodec.decode(SessionCodec.encode(session))
        assertNotNull(decoded)
        assertEquals(session, decoded)
    }

    @Test
    fun corruptSessionJsonDecodesToNull() {
        assertNull(SessionCodec.decode("not json"))
        assertNull(SessionCodec.decode(""))
        assertNull(SessionCodec.decode(null))
    }

    @Test
    fun restoredCheckpointSurvivesAFullSaveLoadResumeCycle() {
        var clock = 500L
        val controller = NarrationController(now = { clock })
        controller.start()
        controller.onLineCompleted(0, controller.snapshot().generation)
        controller.onCough(0.77f, "HYBRID")

        // What the service does: serialise, kill the process, deserialise.
        val json = SessionCodec.encode(controller.exportSession(clock))

        val revived = NarrationController(now = { ++clock })
        val decoded = SessionCodec.decode(json)
        assertNotNull(decoded)
        revived.restore(decoded)
        assertEquals(PlaybackStatus.PAUSED, revived.snapshot().status)
        assertEquals("L02", revived.snapshot().checkpoint?.lineId)

        val speaks = revived.resume(CommandSource.VOICE, "play")
            .filterIsInstance<NarrationAction.SpeakLine>()
        assertEquals("L02", speaks.single().lineId)
    }

    @Test
    fun settingsAndCalibrationRoundTrip() {
        val settings = DetectorSettings(
            backend = DetectorBackend.YAMNET,
            sensitivity = 0.7f,
            dspThreshold = 0.61f,
            yamnetThreshold = 0.28f,
            cooldownMs = 900,
            voiceSlicesWhilePlaying = true,
        )
        assertEquals(settings, SettingsCodec.decode(SettingsCodec.encode(settings)))
        // The microphone handover is a real detection gap and must be visible as one.
        assertEquals(0.25f, settings.detectorGapFraction, 0.001f)
        assertEquals(0f, settings.copy(voiceSlicesWhilePlaying = false).detectorGapFraction, 0.001f)

        val calibration = CalibrationResult(0.62f, 12, 9, 0.92f, 0.03f, 0.21f, 99L)
        assertEquals(calibration, CalibrationCodec.decode(CalibrationCodec.encode(calibration)))
    }

    @Test
    fun unknownBackendFallsBackToHybrid() {
        val decoded = SettingsCodec.decode("""{"backend":"MAGIC","sensitivity":0.4}""")
        assertNotNull(decoded)
        assertEquals(DetectorBackend.HYBRID, decoded!!.backend)
        assertEquals(0.4f, decoded.sensitivity, 0.001f)
    }
}

class CalibrationTest {

    @Test
    fun thresholdSeparatesTwoDistinctClasses() {
        val positives = listOf(0.72f, 0.78f, 0.81f, 0.9f, 0.85f)
        val negatives = listOf(0.2f, 0.31f, 0.25f, 0.33f, 0.28f)
        val result = Calibrator.compute(positives, negatives, targetFpr = 0.05f, createdAtEpochMs = 5L)
        assertTrue("threshold ${result.threshold} must sit between the classes", result.threshold > 0.33f)
        assertTrue(result.threshold <= 0.72f)
        assertEquals(1f, result.truePositiveRate, 0.001f)
        assertEquals(0f, result.falsePositiveRate, 0.001f)
        assertEquals(5, result.positives)
        assertEquals(5, result.negatives)
        assertTrue(result.margin > 0f)
    }

    @Test
    fun overlappingClassesKeepTheFalsePositiveBudget() {
        // Two background windows score like coughs; the 10% budget allows one of ten.
        val positives = listOf(0.6f, 0.7f, 0.8f, 0.75f)
        val negatives = listOf(0.1f, 0.2f, 0.65f, 0.68f, 0.05f, 0.15f, 0.12f, 0.2f, 0.18f, 0.09f)
        val result = Calibrator.compute(positives, negatives, targetFpr = 0.10f)
        assertTrue(result.falsePositiveRate <= 0.10f)
        // 0.65 and 0.68 are the confusing background windows; a 10% budget permits only one.
        assertTrue(result.threshold >= 0.68f)
    }

    @Test
    fun withoutNegativesItFallsBackBelowTheObservedCoughScores() {
        val result = Calibrator.compute(listOf(0.8f, 0.9f), emptyList())
        assertEquals(2, result.positives)
        assertEquals(0, result.negatives)
        assertTrue(result.threshold < 0.8f)
    }

    @Test
    fun emptyInputStillProducesAUsableThreshold() {
        val result = Calibrator.compute(emptyList(), emptyList())
        assertEquals(0.55f, result.threshold, 0.001f)
    }
}

class MetricsTest {

    @Test
    fun latencyStatisticsAreComputedFromRecordedStops() {
        val metrics = DetectionMetrics()
        listOf(20L, 40L, 60L, 80L, 100L, 120L, 140L, 160L, 180L, 200L).forEach {
            metrics.onCoughPause(it)
        }
        val snapshot = metrics.snapshot()
        assertEquals(10, snapshot.latency.count)
        assertEquals(110f, snapshot.latency.avgMs, 0.01f)
        assertEquals(200L, snapshot.latency.maxMs)
        assertEquals(20L, snapshot.latency.minMs)
        assertEquals(180L, snapshot.latency.p95Ms)
    }

    @Test
    fun precisionAndRecallFollowOperatorLabels() {
        val metrics = DetectionMetrics()
        repeat(8) { metrics.onCoughPause(30L) }
        repeat(2) { metrics.onFalsePositive() }
        repeat(2) { metrics.onMissedCough() }
        val snapshot = metrics.snapshot()
        assertEquals(0.8f, snapshot.precision, 0.001f)
        assertEquals(0.8f, snapshot.recall, 0.001f)
    }

    @Test
    fun gateRejectionsAreCountedByKind() {
        val metrics = DetectionMetrics()
        metrics.onGateRejected(GateResult.RejectedCooldown(120L))
        metrics.onGateRejected(GateResult.RejectedLowScore(0.2f, 0.5f))
        metrics.onGateRejected(GateResult.RejectedLowScore(0.3f, 0.5f))
        val snapshot = metrics.snapshot()
        assertEquals(1, snapshot.cooldownSuppressed)
        assertEquals(2, snapshot.lowScoreRejections)
    }

    @Test
    fun resetClearsEverything() {
        val metrics = DetectionMetrics()
        metrics.onCoughPause(10L)
        metrics.onFalsePositive()
        metrics.onMissedCough()
        metrics.reset()
        val snapshot = metrics.snapshot()
        assertEquals(0, snapshot.coughPauses)
        assertEquals(0, snapshot.falsePositives)
        assertEquals(0, snapshot.missedCoughs)
        assertEquals(0, snapshot.latency.count)
    }

    @Test
    fun noDataYieldsZeroPrecisionAndRecallRatherThanNaN() {
        val snapshot = DetectionMetrics().snapshot()
        assertEquals(0f, snapshot.precision, 0.001f)
        assertEquals(0f, snapshot.recall, 0.001f)
    }
}

class VoiceCommandsTest {

    @Test
    fun recognisesTheRestrictedVocabulary() {
        assertEquals(VoiceCommand.PLAY, VoiceCommands.parse("play"))
        assertEquals(VoiceCommand.PLAY, VoiceCommands.parse("Play."))
        assertEquals(VoiceCommand.RESUME, VoiceCommands.parse("please resume the story"))
        assertEquals(VoiceCommand.PAUSE, VoiceCommands.parse("Pause now"))
        assertEquals(VoiceCommand.STOP, VoiceCommands.parse("stop"))
        assertEquals(VoiceCommand.RESUME, VoiceCommands.parse("continue"))
    }

    @Test
    fun lastCommandWordWins() {
        assertEquals(VoiceCommand.RESUME, VoiceCommands.parse("pause, no, resume"))
        assertEquals(VoiceCommand.PAUSE, VoiceCommands.parse("play then pause"))
    }

    @Test
    fun unrelatedSpeechIsIgnored() {
        assertNull(VoiceCommands.parse("the lantern keeper had not spoken"))
        assertNull(VoiceCommands.parse(""))
        assertNull(VoiceCommands.parse(null))
        assertNull(VoiceCommands.parse("replay that bit differently"))
    }
}

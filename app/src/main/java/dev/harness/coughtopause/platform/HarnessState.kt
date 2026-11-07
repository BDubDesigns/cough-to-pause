package dev.harness.coughtopause.platform

import dev.harness.coughtopause.core.CalibrationResult
import dev.harness.coughtopause.core.CommandSource
import dev.harness.coughtopause.core.Counters
import dev.harness.coughtopause.core.DetectionEvent
import dev.harness.coughtopause.core.DetectorBackend
import dev.harness.coughtopause.core.DetectorSettings
import dev.harness.coughtopause.core.MetricsSnapshot
import dev.harness.coughtopause.core.NarrationSnapshot
import dev.harness.coughtopause.core.NarrationScript
import dev.harness.coughtopause.core.PlaybackStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A detection as shown on the diagnostics screen (adds the end-to-end latency). */
data class DetectionView(
    val score: Float,
    val backend: DetectorBackend,
    val peakDb: Float,
    val snrDb: Float,
    val latencyMs: Long?,
    val atMs: Long,
    val accepted: Boolean,
)

/** Microphone / output route reported by AudioManager. */
data class RouteInfo(
    val inputName: String = "unknown",
    val outputName: String = "unknown",
    val isBluetoothInput: Boolean = false,
    val isBluetoothOutput: Boolean = false,
    val sampleRateHz: Int = 0,
    val aecRequested: Boolean = false,
    val aecActive: Boolean = false,
) {
    val summary: String
        get() = "in: $inputName · out: $outputName${if (isBluetoothOutput) " (BT)" else ""}"
}

enum class VoiceStatus {
    OFF,
    STARTING,
    LISTENING,

    /** Recognizer stopped after an error; restarting. */
    RETRYING,

    /** No offline recogniser on this device. Voice commands are unavailable. */
    UNAVAILABLE,
}

data class BatteryView(
    val levelPercent: Int = -1,
    val charging: Boolean = false,
    val currentNowUa: Int = Int.MIN_VALUE,
    val temperatureC: Float = Float.NaN,
    val drainMilliAmpAvg: Float = Float.NaN,
    val batteryPctPerHour: Float = Float.NaN,
    val note: String = "no sample yet",
)

data class UiState(
    val serviceRunning: Boolean = false,
    val narration: NarrationSnapshot = emptyNarration(),
    val metrics: MetricsSnapshot = MetricsSnapshot(0, 0, 0, 0, 0, dev.harness.coughtopause.core.LatencyStats(0, 0f, 0L, 0L, 0L)),
    val lastDetection: DetectionView? = null,
    val route: RouteInfo = RouteInfo(),
    val voiceStatus: VoiceStatus = VoiceStatus.OFF,
    val voiceLastHeard: String? = null,
    val settings: DetectorSettings = DetectorSettings(),
    val calibration: CalibrationResult? = null,
    val battery: BatteryView = BatteryView(),
    val micStatus: String = "stopped",
    val modelStatus: String = "not loaded",
    val calibrationProgress: String? = null,
    val events: List<String> = emptyList(),
    val error: String? = null,
    val lastResumeSource: CommandSource? = null,
) {
    val status: PlaybackStatus get() = narration.status
}

fun emptyNarration(): NarrationSnapshot = NarrationSnapshot(
    scriptId = NarrationScript.SCRIPT_ID,
    status = PlaybackStatus.IDLE,
    currentLineIndex = -1,
    currentLineId = null,
    checkpoint = null,
    generation = 0L,
    counters = Counters(),
    lastEvent = null,
)

/**
 * Process-wide UI state. The service owns every mutation; the Compose UI only observes.
 * A single process, a single writer: no binding ceremony needed for a diagnostics harness.
 */
object HarnessState {

    private const val MAX_EVENTS = 120
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun update(block: (UiState) -> UiState) {
        _state.update(block)
    }

    fun log(message: String) {
        // Mirror to logcat: an on-device playtest without a cable still needs a readable trace.
        android.util.Log.i("CoughToPause", message)
        val stamp = "%tH:%<tM:%<tS".format(System.currentTimeMillis())
        _state.update { s -> s.copy(events = (listOf("$stamp  $message") + s.events).take(MAX_EVENTS)) }
    }

    fun setError(message: String?) {
        _state.update { it.copy(error = message) }
    }

    fun onDetection(event: DetectionEvent, latencyMs: Long?, accepted: Boolean) {
        _state.update {
            it.copy(
                lastDetection = DetectionView(
                    score = event.score,
                    backend = event.backend,
                    peakDb = event.peakDb,
                    snrDb = event.snrDb,
                    latencyMs = latencyMs,
                    atMs = event.timestampMs,
                    accepted = accepted,
                ),
            )
        }
    }

    fun reset() {
        _state.value = UiState()
    }
}

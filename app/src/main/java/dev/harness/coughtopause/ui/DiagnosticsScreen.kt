package dev.harness.coughtopause.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.harness.coughtopause.platform.UiState
import java.util.Locale

/**
 * Local debug screen. Everything here is measured, not assumed: confidence of the last event,
 * the audio route that was actually opened, the stop latency, and operator-labelled errors.
 */
@Composable
fun DiagnosticsScreen(
    state: UiState,
    onBack: () -> Unit,
    onFalsePositive: () -> Unit,
    onMissedCough: () -> Unit,
    onResetMetrics: () -> Unit,
    onRefreshRoute: () -> Unit,
) {
    val narration = state.narration
    val metrics = state.metrics
    val detection = state.lastDetection

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Diagnostics", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = onBack) { Text("Back") }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Playback", style = MaterialTheme.typography.titleMedium)
                SectionRow("service", if (state.serviceRunning) "running" else "stopped")
                SectionRow("state", statusLabel(narration.status))
                SectionRow("line", "${narration.currentLineId ?: "—"} (idx ${narration.currentLineIndex})")
                SectionRow("generation", narration.generation.toString())
                SectionRow(
                    "checkpoint",
                    narration.checkpoint?.let { "${it.lineId} · ${it.reason}" } ?: "none",
                )
                SectionRow("completed lines", narration.counters.completedLines.toString())
                SectionRow("resumes", narration.counters.resumes.toString())
                SectionRow("ignored coughs", narration.counters.ignoredCoughs.toString())
                SectionRow("stale TTS callbacks", narration.counters.staleCallbacks.toString())
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Last detection", style = MaterialTheme.typography.titleMedium)
                if (detection == null) {
                    Text("no detection yet", style = TelemetryStyle)
                } else {
                    SectionRow("score", "%.3f".format(detection.score))
                    SectionRow("backend", detection.backend.name)
                    SectionRow("accepted", if (detection.accepted) "yes (paused)" else "no")
                    SectionRow("peak", "%.1f dBFS".format(detection.peakDb))
                    SectionRow("snr", "%.1f dB".format(detection.snrDb))
                    SectionRow(
                        "detect → audio stop",
                        detection.latencyMs?.let { "$it ms" } ?: "—",
                    )
                }
                HorizontalDivider()
                SectionRow(
                    "latency stats",
                    "avg %.0f / p95 %d / max %d ms (n=%d)".format(
                        metrics.latency.avgMs,
                        metrics.latency.p95Ms,
                        metrics.latency.maxMs,
                        metrics.latency.count,
                    ),
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Audio route", style = MaterialTheme.typography.titleMedium)
                SectionRow("input", state.route.inputName)
                SectionRow("output", state.route.outputName)
                SectionRow("bluetooth", "in=${state.route.isBluetoothInput} out=${state.route.isBluetoothOutput}")
                SectionRow("record rate", "${state.route.sampleRateHz} Hz")
                SectionRow(
                    "echo cancel",
                    "requested=${state.route.aecRequested} active=${state.route.aecActive}",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onRefreshRoute) { Text("Refresh route") }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Detector & voice", style = MaterialTheme.typography.titleMedium)
                SectionRow("backend", state.settings.backend.name)
                SectionRow("sensitivity", "%.2f".format(state.settings.sensitivity))
                SectionRow("dsp threshold", "%.3f".format(state.settings.effectiveDspThreshold()))
                SectionRow("yamnet threshold", "%.3f".format(state.settings.effectiveYamnetThreshold()))
                SectionRow("cooldown", "${state.settings.cooldownMs} ms")
                SectionRow("window / hop", "${state.settings.windowMs} / ${state.settings.hopMs} ms")
                SectionRow("microphone", state.micStatus)
                SectionRow("model", state.modelStatus)
                SectionRow("voice", state.voiceStatus.name)
                state.voiceLastHeard?.let { SectionRow("last phrase", it) }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Operator-labelled accuracy", style = MaterialTheme.typography.titleMedium)
                SectionRow("cough pauses (detector)", metrics.coughPauses.toString())
                SectionRow("false positives", metrics.falsePositives.toString())
                SectionRow("missed coughs", metrics.missedCoughs.toString())
                SectionRow("precision", "%.0f%%".format(metrics.precision * 100))
                SectionRow("recall", "%.0f%%".format(metrics.recall * 100))
                SectionRow("gate: cooldown drops", metrics.cooldownSuppressed.toString())
                SectionRow("gate: below threshold", metrics.lowScoreRejections.toString())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onFalsePositive) { Text("Not a cough") }
                    OutlinedButton(onClick = onMissedCough) { Text("Missed") }
                }
                TextButton(onClick = onResetMetrics) { Text("Reset counters") }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Battery", style = MaterialTheme.typography.titleMedium)
                val b = state.battery
                SectionRow("level", if (b.levelPercent >= 0) "${b.levelPercent}%" else "—")
                SectionRow("charging", b.charging.toString())
                SectionRow(
                    "current",
                    if (b.currentNowUa != Int.MIN_VALUE) "%.1f mA".format(b.currentNowUa / 1000f) else "—",
                )
                SectionRow(
                    "avg drain",
                    if (!b.drainMilliAmpAvg.isNaN()) "%.1f mA".format(b.drainMilliAmpAvg) else "—",
                )
                SectionRow(
                    "rate",
                    if (!b.batteryPctPerHour.isNaN()) "%.1f %%/h".format(Locale.US, b.batteryPctPerHour) else "—",
                )
                SectionRow("note", b.note)
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Event log", style = MaterialTheme.typography.titleMedium)
                if (state.events.isEmpty()) {
                    Text("nothing logged yet", style = TelemetryStyle)
                }
                state.events.take(60).forEach { line ->
                    Text(line, style = TelemetryStyle)
                }
            }
        }
        Spacer(Modifier.padding(8.dp))
    }
}

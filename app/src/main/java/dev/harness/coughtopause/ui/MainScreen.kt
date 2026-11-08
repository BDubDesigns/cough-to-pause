package dev.harness.coughtopause.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.harness.coughtopause.core.DetectorBackend
import dev.harness.coughtopause.core.NarrationScript
import dev.harness.coughtopause.core.PlaybackStatus
import dev.harness.coughtopause.platform.HarnessState
import dev.harness.coughtopause.platform.UiState
import java.util.Locale

@Composable
fun MainScreen(
    state: UiState,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onBackendChange: (DetectorBackend, Float) -> Unit,
    onVoiceSlicesChange: (Boolean) -> Unit,
    onCalibrateCough: () -> Unit,
    onCalibrateBackground: () -> Unit,
    onApplyCalibration: () -> Unit,
    onClearCalibration: () -> Unit,
    onFalsePositive: () -> Unit,
    onMissedCough: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenAdventure: () -> Unit,
) {
    val narration = state.narration
    val status = narration.status

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Cough-to-Pause", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "M0 audio harness · native TTS · local detection · no network",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        StatusCard(state)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onStart, modifier = Modifier.weight(1f)) { Text("Start") }
            if (status == PlaybackStatus.PLAYING) {
                Button(onClick = onPause, modifier = Modifier.weight(1f)) { Text("Pause") }
            } else {
                Button(
                    onClick = onResume,
                    enabled = status == PlaybackStatus.PAUSED,
                    modifier = Modifier.weight(1f),
                ) { Text("Resume") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f)) { Text("Stop") }
            OutlinedButton(onClick = onOpenDiagnostics, modifier = Modifier.weight(1f)) {
                Text("Diagnostics")
            }
        }
        Button(onClick = onOpenAdventure, modifier = Modifier.fillMaxWidth()) {
            Text("Adventure →")
        }

        DetectorCard(state, onBackendChange, onVoiceSlicesChange)

        CalibrationCard(
            state = state,
            onCalibrateCough = onCalibrateCough,
            onCalibrateBackground = onCalibrateBackground,
            onApply = onApplyCalibration,
            onClear = onClearCalibration,
        )

        MetricsCard(state, onFalsePositive, onMissedCough)

        val error = state.error
        if (error != null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(
                    error,
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun StatusCard(state: UiState) {
    val narration = state.narration
    val checkpoint = narration.checkpoint
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(narration.status)
                Spacer(Modifier.width(8.dp))
                Text(statusLabel(narration.status), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Text(
                    narration.currentLineId ?: "—",
                    style = TelemetryStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // The controller reports the text from whichever script it currently holds (harness or
            // an RPG scene), so no lookup against the harness script here.
            val lineText = narration.currentLineText
            Text(
                lineText ?: "Press Start to narrate the twelve-line harness script.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (checkpoint != null) {
                Text(
                    "checkpoint: ${checkpoint.lineId} · reason=${checkpoint.reason} · resumes from the top of that line",
                    style = TelemetryStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "mic: ${state.micStatus} · voice: ${state.voiceStatus}" +
                    (state.voiceLastHeard?.let { " · heard \"$it\"" } ?: ""),
                style = TelemetryStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DetectorCard(
    state: UiState,
    onBackendChange: (DetectorBackend, Float) -> Unit,
    onVoiceSlicesChange: (Boolean) -> Unit,
) {
    var sensitivity by remember { mutableFloatStateOf(state.settings.sensitivity) }
    var backend by remember { mutableStateOf(state.settings.backend) }
    var dirty by remember { mutableStateOf(false) }

    LaunchedEffect(state.settings.backend, state.settings.sensitivity) {
        if (!dirty) {
            sensitivity = state.settings.sensitivity
            backend = state.settings.backend
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Detector", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DetectorBackend.entries.forEach { candidate ->
                    FilterChip(
                        selected = backend == candidate,
                        onClick = {
                            backend = candidate
                            dirty = true
                        },
                        label = { Text(candidate.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    )
                }
            }
            Text(
                String.format(
                    Locale.US,
                    "sensitivity %.2f · dsp thr %.2f · yamnet thr %.2f · cooldown %d ms",
                    sensitivity,
                    state.settings.effectiveDspThreshold(),
                    state.settings.effectiveYamnetThreshold(),
                    state.settings.cooldownMs,
                ),
                style = TelemetryStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = sensitivity,
                onValueChange = {
                    sensitivity = it
                    dirty = true
                },
                valueRange = 0f..1f,
                steps = 19,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        onBackendChange(backend, sensitivity)
                        dirty = false
                    },
                    enabled = dirty,
                ) { Text("Apply") }

                // Checkbox rather than a switch: it is an explicit trade, not a preference.
                androidx.compose.material3.Checkbox(
                    checked = state.settings.voiceSlicesWhilePlaying,
                    onCheckedChange = onVoiceSlicesChange,
                )
                Text(
                    "Voice while narrating",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
            if (state.settings.voiceSlicesWhilePlaying) {
                Text(
                    "Detection is off for 1.5 s of every 6 s while narrating, so a spoken \"Pause\" works. " +
                        "Turn this off for maximum cough detection.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                state.modelStatus,
                style = TelemetryStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CalibrationCard(
    state: UiState,
    onCalibrateCough: () -> Unit,
    onCalibrateBackground: () -> Unit,
    onApply: () -> Unit,
    onClear: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Calibration", style = MaterialTheme.typography.titleMedium)
            Text(
                "Cough deliberately for 5 s, then let the room talk for 5 s. Thresholds are chosen " +
                    "from the score distributions captured here — nothing is uploaded.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCalibrateCough, modifier = Modifier.weight(1f)) {
                    Text("Capture cough")
                }
                OutlinedButton(onClick = onCalibrateBackground, modifier = Modifier.weight(1f)) {
                    Text("Capture background")
                }
            }
            Text(
                state.calibrationProgress ?: "no samples captured in this run",
                style = TelemetryStyle,
            )
            val calibration = state.calibration
            if (calibration != null) {
                Text(
                    "applied: thr %.2f · tpr %.0f%% · fpr %.0f%% · n=%d/%d".format(
                        calibration.threshold,
                        calibration.truePositiveRate * 100,
                        calibration.falsePositiveRate * 100,
                        calibration.positives,
                        calibration.negatives,
                    ),
                    style = TelemetryStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onApply) { Text("Apply calibration") }
                TextButton(onClick = onClear) { Text("Clear") }
            }
        }
    }
}

@Composable
private fun MetricsCard(state: UiState, onFalsePositive: () -> Unit, onMissedCough: () -> Unit) {
    val metrics = state.metrics
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Measurements (this run)", style = MaterialTheme.typography.titleMedium)
            Text(
                "cough pauses ${metrics.coughPauses} · precision %.0f%% · recall %.0f%%".format(
                    metrics.precision * 100,
                    metrics.recall * 100,
                ),
                style = TelemetryStyle,
            )
            Text(
                "latency avg %.0f ms · p95 %d ms · max %d ms · n=%d".format(
                    metrics.latency.avgMs,
                    metrics.latency.p95Ms,
                    metrics.latency.maxMs,
                    metrics.latency.count,
                ),
                style = TelemetryStyle,
            )
            Text(
                "operator: false positives ${metrics.falsePositives} · missed coughs ${metrics.missedCoughs} " +
                    "· cooldown suppressed ${metrics.cooldownSuppressed}",
                style = TelemetryStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onFalsePositive, modifier = Modifier.weight(1f)) {
                    Text("Was not a cough")
                }
                OutlinedButton(onClick = onMissedCough, modifier = Modifier.weight(1f)) {
                    Text("Missed a cough")
                }
            }
        }
    }
}

@Composable
fun StatusDot(status: PlaybackStatus) {
    val color = when (status) {
        PlaybackStatus.PLAYING -> MaterialTheme.colorScheme.primary
        PlaybackStatus.PAUSED -> MaterialTheme.colorScheme.tertiary
        PlaybackStatus.COMPLETED -> MaterialTheme.colorScheme.secondary
        PlaybackStatus.IDLE -> MaterialTheme.colorScheme.outline
    }
    androidx.compose.foundation.Canvas(modifier = Modifier.height(10.dp).width(10.dp)) {
        drawCircle(color = color, radius = size.minDimension / 2f)
    }
}

fun statusLabel(status: PlaybackStatus): String = when (status) {
    PlaybackStatus.PLAYING -> "Narrating"
    PlaybackStatus.PAUSED -> "Paused"
    PlaybackStatus.COMPLETED -> "Script finished"
    PlaybackStatus.IDLE -> "Idle"
}

@Composable
fun SectionRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = TelemetryStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(value, style = TelemetryStyle)
    }
}

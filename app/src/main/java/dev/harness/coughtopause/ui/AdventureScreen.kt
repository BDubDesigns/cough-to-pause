package dev.harness.coughtopause.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.harness.coughtopause.adventure.AdventureSnapshot
import dev.harness.coughtopause.adventure.AdventureUiState
import dev.harness.coughtopause.adventure.VoiceState
import dev.harness.coughtopause.core.PlaybackStatus
import dev.harness.coughtopause.platform.UiState

@Composable
fun AdventureScreen(
    adventure: AdventureUiState,
    harness: UiState,
    backgrounds: List<Pair<String, String>>,
    onBegin: (name: String, backgroundId: String) -> Unit,
    onRestore: () -> Unit,
    onAct: (String) -> Unit,
    onSpeak: () -> Unit,
    onStopVoice: () -> Unit,
    onNarrateAgain: () -> Unit,
    onBack: () -> Unit,
) {
    val snapshot = adventure.snapshot
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Adventure", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = onBack) { Text("Harness") }
        }

        Text(
            adventure.narrationSource,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (snapshot == null) {
            CharacterCreationCard(backgrounds, onBegin, onRestore, adventure.busy)
        } else {
            LocationCard(snapshot, harness)
            CharacterCard(snapshot)
            snapshot.lastRoll?.let { roll ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Last roll", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${roll.skill}: d20 ${roll.natural ?: "—"} · total ${roll.total ?: "—"} vs DC ${roll.dc ?: "—"}",
                            style = TelemetryStyle,
                        )
                        Text(
                            "${roll.outcome}${if (roll.xpGained > 0) " · +${roll.xpGained} XP" else ""}",
                            style = TelemetryStyle,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            ActionCard(adventure, onAct, onSpeak, onStopVoice, onNarrateAgain)
            TranscriptCard(snapshot)
        }

        adventure.message?.let { message ->
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.padding(8.dp))
    }
}

@Composable
private fun CharacterCreationCard(
    backgrounds: List<Pair<String, String>>,
    onBegin: (String, String) -> Unit,
    onRestore: () -> Unit,
    busy: Boolean,
) {
    var name by remember { mutableStateOf("Mira") }
    var background by remember { mutableStateOf(backgrounds.firstOrNull()?.first ?: "caravan_guard") }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Create a character", style = MaterialTheme.typography.titleMedium)
            Text(
                "Attributes are rolled 4d6-drop-lowest by the rules engine and assigned by background. " +
                    "The starting region is the validated fallback world.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                backgrounds.forEach { (id, label) ->
                    FilterChip(
                        selected = background == id,
                        onClick = { background = id },
                        label = { Text(label) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onBegin(name, background) }, enabled = !busy) { Text("Begin") }
                OutlinedButton(onClick = onRestore, enabled = !busy) { Text("Restore saved") }
            }
        }
    }
}

@Composable
private fun LocationCard(snapshot: AdventureSnapshot, harness: UiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(snapshot.locationName, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                Text("day ${snapshot.day}", style = TelemetryStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(snapshot.locationDescription, style = MaterialTheme.typography.bodyMedium)
            if (snapshot.npcs.isNotEmpty()) {
                Text("Here: " + snapshot.npcs.joinToString(" · "), style = TelemetryStyle)
            }
            if (snapshot.exits.isNotEmpty()) {
                Text("Exits: " + snapshot.exits.joinToString(", "), style = TelemetryStyle)
            }
            HorizontalDivider()
            Text(
                "narration: ${statusLabel(harness.narration.status)}" +
                    (harness.narration.checkpoint?.let { " · paused at ${it.lineId}" } ?: ""),
                style = TelemetryStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CharacterCard(snapshot: AdventureSnapshot) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${snapshot.characterName} · ${snapshot.background}", style = MaterialTheme.typography.titleMedium)
            Text(
                "HP ${snapshot.hp}/${snapshot.maxHp} · MP ${snapshot.mp}/${snapshot.maxMp} · ${snapshot.gold} gold",
                style = TelemetryStyle,
            )
            Text(
                snapshot.attributes.joinToString("  ") { (k, v) -> "$k $v" },
                style = TelemetryStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "XP: " + snapshot.skills.joinToString("  ") { (k, v) -> "$k $v" },
                style = TelemetryStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ActionCard(
    adventure: AdventureUiState,
    onAct: (String) -> Unit,
    onSpeak: () -> Unit,
    onStopVoice: () -> Unit,
    onNarrateAgain: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val listening = adventure.voice == VoiceState.Listening
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("What do you do?", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Type an action") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        onAct(text)
                        text = ""
                    },
                    enabled = text.isNotBlank() && !adventure.busy,
                ) { Text("Send") }
                OutlinedButton(onClick = if (listening) onStopVoice else onSpeak) {
                    Text(if (listening) "Stop" else "Speak")
                }
                OutlinedButton(onClick = onNarrateAgain) { Text("Repeat") }
            }
            Text(
                when (val v = adventure.voice) {
                    VoiceState.Idle -> "voice: idle"
                    VoiceState.Listening -> "voice: listening…"
                    is VoiceState.Captured -> "voice: \"${v.text}\""
                    is VoiceState.Failed -> "voice: ${v.reason}"
                    VoiceState.TimedOut -> "voice: timed out — type instead"
                },
                style = TelemetryStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TranscriptCard(snapshot: AdventureSnapshot) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Journal", style = MaterialTheme.typography.titleMedium)
            snapshot.transcript.takeLast(14).forEach { line ->
                Text(line, style = TelemetryStyle)
            }
        }
    }
}

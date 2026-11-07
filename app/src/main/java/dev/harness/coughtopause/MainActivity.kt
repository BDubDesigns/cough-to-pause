package dev.harness.coughtopause

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.harness.coughtopause.platform.HarnessState
import dev.harness.coughtopause.platform.SessionStore
import dev.harness.coughtopause.service.NarrationService
import dev.harness.coughtopause.ui.DiagnosticsScreen
import dev.harness.coughtopause.ui.HarnessTheme
import dev.harness.coughtopause.ui.MainScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HarnessTheme {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    HarnessApp()
                }
            }
        }
    }
}

private object Screens {
    const val MAIN = 0
    const val DIAGNOSTICS = 1
}

@Composable
private fun HarnessApp() {
    val context = LocalContext.current
    val state by HarnessState.state.collectAsStateWithLifecycle()
    val store = remember { SessionStore(context) }
    var screen by remember { mutableIntStateOf(Screens.MAIN) }
    var hasMic by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        hasMic = result[Manifest.permission.RECORD_AUDIO] == true
    }

    LaunchedEffect(Unit) {
        if (!hasMic) {
            val permissions = buildList {
                add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    add(Manifest.permission.BLUETOOTH_CONNECT)
                }
            }
            permissionLauncher.launch(permissions.toTypedArray())
        }
        // Bring the foreground service (and any restored checkpoint) up without starting audio.
        NarrationService.ensureRunning(context)
    }

    BackHandler(enabled = screen == Screens.DIAGNOSTICS) { screen = Screens.MAIN }

    if (!hasMic) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text("Microphone permission is required", style = MaterialTheme.typography.titleLarge)
            Text(
                "This harness detects a cough on the device. Audio is never written to storage " +
                    "or uploaded; only a 0.5 s window is analysed in memory.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
            )
            Button(onClick = {
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.POST_NOTIFICATIONS,
                        Manifest.permission.BLUETOOTH_CONNECT,
                    ),
                )
            }) { Text("Grant microphone access") }
        }
        return
    }

    when (screen) {
        Screens.DIAGNOSTICS -> DiagnosticsScreen(
            state = state,
            onBack = { screen = Screens.MAIN },
            onFalsePositive = { NarrationService.markFalsePositive(context) },
            onMissedCough = { NarrationService.markMissed(context) },
            onResetMetrics = { NarrationService.resetMetrics(context) },
            onRefreshRoute = { NarrationService.send(context, NarrationService.ACTION_REFRESH_ROUTE) },
        )

        else -> MainScreen(
            state = state,
            onStart = { NarrationService.startNarration(context) },
            onPause = { NarrationService.pause(context) },
            onResume = { NarrationService.resume(context) },
            onStop = { NarrationService.stopNarration(context) },
            onBackendChange = { backend, sensitivity ->
                val updated = state.settings.copy(backend = backend, sensitivity = sensitivity)
                store.saveSettings(updated)
                NarrationService.reloadSettings(context)
            },
            onCalibrateCough = { NarrationService.calibrate(context, "cough", 5000) },
            onCalibrateBackground = { NarrationService.calibrate(context, "background", 5000) },
            onApplyCalibration = { NarrationService.applyCalibration(context) },
            onClearCalibration = { NarrationService.clearCalibration(context) },
            onFalsePositive = { NarrationService.markFalsePositive(context) },
            onMissedCough = { NarrationService.markMissed(context) },
            onOpenDiagnostics = { screen = Screens.DIAGNOSTICS },
        )
    }
}

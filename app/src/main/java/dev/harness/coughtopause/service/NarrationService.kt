package dev.harness.coughtopause.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.speech.SpeechRecognizer
import android.content.ComponentName
import android.media.AudioManager as FrameworkAudioManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.harness.coughtopause.MainActivity
import dev.harness.coughtopause.R
import dev.harness.coughtopause.core.CalibrationResult
import dev.harness.coughtopause.core.Calibrator
import dev.harness.coughtopause.core.CommandSource
import dev.harness.coughtopause.core.DetectionEvent
import dev.harness.coughtopause.core.DetectionMetrics
import dev.harness.coughtopause.core.DetectorBackend
import dev.harness.coughtopause.core.DetectorSettings
import dev.harness.coughtopause.core.DspResult
import dev.harness.coughtopause.core.GateResult
import dev.harness.coughtopause.core.NarrationAction
import dev.harness.coughtopause.core.NarrationController
import dev.harness.coughtopause.core.NarrationScript
import dev.harness.coughtopause.core.NarrationSnapshot
import dev.harness.coughtopause.core.ScriptLine
import dev.harness.coughtopause.core.PauseReason
import dev.harness.coughtopause.core.PlaybackStatus
import dev.harness.coughtopause.core.VoiceCommand
import dev.harness.coughtopause.platform.AudioRouteMonitor
import dev.harness.coughtopause.platform.BatteryProbe
import dev.harness.coughtopause.platform.CommandListener
import dev.harness.coughtopause.platform.CoughAudioEngine
import dev.harness.coughtopause.platform.HarnessState
import dev.harness.coughtopause.platform.SessionStore
import dev.harness.coughtopause.platform.TtsNarrator
import dev.harness.coughtopause.platform.VoiceStatus

/**
 * Foreground service that owns the whole audio experiment:
 * TTS narration, microphone detection, voice commands, media buttons, persistence.
 *
 * Every state transition runs on the main looper, so "cough" and "tts-done" can never interleave
 * halfway through a transition. The pure state machine in `core` decides, this class executes.
 */
class NarrationService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val metrics = DetectionMetrics()

    private lateinit var store: SessionStore
    private lateinit var controller: NarrationController
    private var tts: TtsNarrator? = null
    private var audio: CoughAudioEngine? = null
    private var voice: CommandListener? = null
    private var routeMonitor: AudioRouteMonitor? = null
    private var battery: BatteryProbe? = null
    private var mediaSession: MediaSession? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var focusRequest: AudioFocusRequest? = null

    private var settings: DetectorSettings = DetectorSettings()
    private var running = false
    private var startedForeground = false
    private var ttsDetail = "initialising"
    private var ttsQueued = false
    private val micExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "mic-policy")
    }
    private var lastPolicyStatus: PlaybackStatus? = null
    private var sliceActive = false
    private var lastWatchdogWindows: Long = -1
    private var lastWatchdogEngine: CoughAudioEngine? = null

    private val calibration = CalibrationSession()

    /** Recognition slice while narrating: long enough for a short command, short enough to keep gap small. */
    private val voiceSliceEveryMs = 6_000L
    private val voiceSliceMs = 1_500L

    override fun onCreate() {
        super.onCreate()
        store = SessionStore(this)
        settings = store.loadSettings()
        controller = NarrationController(now = { SystemClock.elapsedRealtime() })
        routeMonitor = AudioRouteMonitor(this)
        battery = BatteryProbe(this)
        metrics.reset()

        val calibrationResult = store.loadCalibration()
        HarnessState.update {
            it.copy(
                settings = settings,
                calibration = calibrationResult,
                metrics = metrics.snapshot(),
            )
        }

        val persisted = store.loadSession()
        // A checkpoint from an RPG scene needs that scene's lines back before it can be restored.
        if (persisted != null && persisted.scriptId != NarrationScript.SCRIPT_ID) {
            val scene = store.loadScene()
            if (scene != null && scene.first == persisted.scriptId) {
                controller.loadScript(scene.first, scene.second)
                HarnessState.log("scene restored: ${scene.first} (${scene.second.size} lines)")
            }
        }
        val restored = controller.restore(persisted)
        if (restored.isNotEmpty()) {
            HarnessState.log("session restored: ${controller.snapshot().status} line=${controller.snapshot().currentLineId}")
        }
        HarnessState.update { it.copy(narration = controller.snapshot()) }

        createNotificationChannel()
        initTts()
        initMediaSession()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundSafely()
        if (!running) {
            running = true
            acquireWakeLock()
            initAudioRuntime()
        }
        HarnessState.update { it.copy(serviceRunning = true, narration = controller.snapshot()) }
        handleIntent(intent)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(screenReceiver) }
        runCatching { micExecutor.shutdownNow() }
        audio?.stop()
        audio = null
        voice?.stop()
        voice = null
        tts?.shutdown()
        tts = null
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        abandonFocus()
        releaseWakeLock()
        running = false
        startedForeground = false
        HarnessState.update {
            it.copy(serviceRunning = false, micStatus = "stopped", voiceStatus = VoiceStatus.OFF)
        }
        store.saveSession(controller.exportSession(System.currentTimeMillis()))
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------------- wiring

    private fun initTts() {
        tts = TtsNarrator(
            context = this,
            onInit = { ready, detail ->
                ttsDetail = detail
                HarnessState.log(if (ready) "TTS ready: $detail" else "TTS problem: $detail")
                if (!ready) HarnessState.setError(detail)
                // Start was pressed before the engine finished initialising: speak now.
                val snapshot = controller.snapshot()
                if (ready && snapshot.status == PlaybackStatus.PLAYING && !ttsQueued) {
                    val line = snapshot.currentLineText
                    if (line != null && snapshot.currentLineId != null) {
                        ttsQueued = true
                        tts?.speak(line, "${snapshot.generation}|${snapshot.currentLineIndex}")
                    }
                }
            },
            onStart = { id -> handler.post { dispatchTtsStart(id) } },
            onDone = { id -> handler.post { dispatchTtsDone(id) } },
            onError = { id, code -> handler.post { dispatchTtsError(id, code) } },
        )
    }

    private fun initAudioRuntime() {
        requestFocus()
        scheduleBatterySample()
        refreshRoute()
        registerScreenReceiver()
        handler.postDelayed(captureWatchdog, 10_000)
        HarnessState.log(
            "voice: on-device recogniser=${SpeechRecognizer.isOnDeviceRecognitionAvailable(this)} " +
                "sdk=${Build.VERSION.SDK_INT}",
        )
        applyMicPolicy(controller.snapshot().status)
    }

    private fun createDetector(): CoughAudioEngine = CoughAudioEngine(
        context = this,
        settingsProvider = { settings },
        onStatus = { text ->
            HarnessState.update { it.copy(micStatus = text) }
            if (!sliceActive) HarnessState.log("mic: $text")
        },
        onDetection = { event -> onDetection(event) },
        onAnalysis = { dsp, yamnetScore, accepted ->
            collectCalibrationSample(dsp, yamnetScore, accepted)
        },
        onGateRejected = { result: GateResult ->
            metrics.onGateRejected(result)
            if (result is GateResult.RejectedCooldown) {
                HarnessState.log("cooldown: suppressed detection (%.0f ms)".format(result.suppressedForMs))
            }
        },
        onCapture = { line ->
            HarnessState.update {
                it.copy(capture = line, captureWindows = captureWindowsSeen)
            }
        },
    )

    @Volatile
    private var captureWindowsSeen: Long = 0L

    private fun setDetectorWanted(wanted: Boolean) {
        if (wanted) {
            if (audio == null) {
                val engine = createDetector()
                if (engine.start()) {
                    audio = engine
                } else {
                    HarnessState.setError("Microphone could not start. Grant RECORD_AUDIO and try again.")
                }
            }
        } else {
            audio?.stop()
            audio = null
        }
    }

    private fun setVoiceWanted(wanted: Boolean) {
        // SpeechRecognizer binds a Handler at construction time, so it must be built on a
        // thread that has a Looper. The mic worker thread deliberately has none.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { setVoiceWanted(wanted) }
            return
        }
        if (wanted) {
            if (voice == null) {
                voice = CommandListener(
                    context = this,
                    onCommand = { command, phrase -> onVoiceCommand(command, phrase) },
                    onStatus = { status, detail ->
                        HarnessState.update { it.copy(voiceStatus = status) }
                        if (detail != null) HarnessState.log("voice: $detail")
                    },
                ).also { it.start() }
            }
        } else {
            voice?.stop()
            voice = null
        }
    }

    /**
     * Microphone arbitration.
     *
     * Android gives capture to one client at a time: while this app holds its own AudioRecord the
     * platform SpeechRecognizer never receives audio and silently never becomes ready (measured on
     * the test Pixel). So the harness hands the microphone to exactly one consumer:
     *
     *  - PAUSED  → recogniser owns it. Coughs are ignored by design while paused, so nothing is lost,
     *              and “Play”/“Resume” is available immediately.
     *  - PLAYING → detector owns it, with a short recognition slice every [voiceSliceEveryMs] so a
     *              spoken “Pause” still lands. Each slice is a known detection gap, reported as part
     *              of the playtest measurements.
     */
    private fun applyMicPolicy(status: PlaybackStatus) {
        lastPolicyStatus = status
        handler.removeCallbacks(voiceSliceRunnable)
        handler.removeCallbacks(voiceSliceEndRunnable)
        sliceActive = false
        when (status) {
            PlaybackStatus.PAUSED -> micExecutor.execute {
                setDetectorWanted(false)
                setVoiceWanted(true)
            }
            PlaybackStatus.PLAYING -> {
                micExecutor.execute {
                    setVoiceWanted(false)
                    setDetectorWanted(true)
                }
                if (settings.voiceSlicesWhilePlaying) {
                    handler.postDelayed(voiceSliceRunnable, voiceSliceEveryMs)
                }
            }
            else -> micExecutor.execute {
                setVoiceWanted(false)
                setDetectorWanted(true)
            }
        }
        HarnessState.log(
            "mic policy: $status → ${if (status == PlaybackStatus.PAUSED) "recogniser" else "detector"}" +
                if (settings.voiceSlicesWhilePlaying) " (voice slices on)" else " (continuous detection)",
        )
    }

    private val voiceSliceRunnable = Runnable { startVoiceSlice() }
    private val voiceSliceEndRunnable = Runnable { endVoiceSlice() }

    private fun startVoiceSlice() {
        if (sliceActive) return
        if (controller.snapshot().status != PlaybackStatus.PLAYING) return
        sliceActive = true
        HarnessState.update { it.copy(micStatus = "handed to speech recogniser (${voiceSliceMs}ms)") }
        micExecutor.execute {
            setDetectorWanted(false)
            setVoiceWanted(true)
        }
        handler.postDelayed(voiceSliceEndRunnable, voiceSliceMs)
    }

    private fun endVoiceSlice() {
        if (!sliceActive) return
        sliceActive = false
        handler.post {
            setVoiceWanted(false)
            handler.postDelayed({
                micExecutor.execute {
                    if (controller.snapshot().status == PlaybackStatus.PLAYING) setDetectorWanted(true)
                }
            }, 200)
        }
        if (controller.snapshot().status == PlaybackStatus.PLAYING) {
            handler.postDelayed(voiceSliceRunnable, voiceSliceEveryMs - voiceSliceMs)
        }
    }

    private fun requestFocus() {
        val am = getSystemService(AudioManager::class.java) ?: return
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change ->
                handler.post {
                    HarnessState.log("audio focus changed: $change")
                    when (change) {
                        AudioManager.AUDIOFOCUS_LOSS,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
                        -> execute(controller.pause(CommandSource.SYSTEM))
                    }
                }
            }
            .build()
        focusRequest = request
        runCatching { am.requestAudioFocus(request) }
    }

    private fun abandonFocus() {
        val am = getSystemService(AudioManager::class.java)
        focusRequest?.let { runCatching { am?.abandonAudioFocusRequest(it) } }
        focusRequest = null
    }

    private fun initMediaSession() {
        val session = MediaSession(this, "cough_to_pause").apply {
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(
                object : MediaSession.Callback() {
                    override fun onPlay() {
                        handler.post { handleMedia(MediaIntent.PLAY) }
                    }

                    override fun onPause() {
                        handler.post { handleMedia(MediaIntent.PAUSE) }
                    }

                    override fun onStop() {
                        // `controller` is shadowed by MediaSession.Callback.getController().
                        handler.post {
                            execute(this@NarrationService.controller.stop(CommandSource.MEDIA_BUTTON))
                        }
                    }

                    override fun onPlayFromMediaId(mediaId: String?, extras: android.os.Bundle?) {
                        handler.post { handleMedia(MediaIntent.PLAY) }
                    }
                },
            )
            isActive = true
        }
        mediaSession = session
        updateMediaSession(controller.snapshot())
    }

    // ---------------------------------------------------------------- intents

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        when (val action = intent.action) {
            ACTION_START_NARRATION -> {
                // The harness button always narrates the harness script, even if an RPG scene
                // was the last thing loaded.
                if (controller.snapshot().scriptId != NarrationScript.SCRIPT_ID) {
                    execute(controller.loadScript(NarrationScript.SCRIPT_ID, NarrationScript.LINES))
                }
                execute(controller.start(CommandSource.UI))
            }
            ACTION_NARRATE_SCENE -> {
                val sceneId = intent.getStringExtra(EXTRA_SCENE_ID) ?: "scene"
                val texts = intent.getStringArrayListExtra(EXTRA_LINES).orEmpty()
                if (texts.isEmpty()) {
                    HarnessState.log("rpg: empty scene ignored")
                } else {
                    val lines = texts.mapIndexed { i, text ->
                        ScriptLine("R%02d".format(i + 1), i, text)
                    }
                    store.saveScene(sceneId, lines)
                    execute(controller.loadScript(sceneId, lines))
                    execute(controller.start(CommandSource.SYSTEM))
                    HarnessState.log("rpg: narrating $sceneId (${lines.size} lines)")
                }
            }
            ACTION_RESUME -> execute(controller.resume(CommandSource.UI))
            ACTION_PAUSE -> execute(controller.pause(CommandSource.UI))
            ACTION_STOP -> execute(controller.stop(CommandSource.UI))
            ACTION_TOGGLE -> handleMedia(MediaIntent.TOGGLE)
            ACTION_RELOAD_SETTINGS -> {
                settings = store.loadSettings()
                audio?.resetGate()
                HarnessState.update { it.copy(settings = settings) }
                applyMicPolicy(controller.snapshot().status)
                HarnessState.log(
                    "settings reloaded: ${settings.backend} sens=${settings.sensitivity} " +
                        "voiceSlices=${settings.voiceSlicesWhilePlaying}",
                )
            }
            ACTION_CALIBRATE_PHASE -> {
                val phase = intent.getStringExtra(EXTRA_PHASE) ?: "cough"
                val duration = intent.getIntExtra(EXTRA_DURATION_MS, 4000)
                startCalibrationPhase(phase, duration)
            }
            ACTION_CALIBRATE_APPLY -> applyCalibration()
            ACTION_CALIBRATE_CLEAR -> {
                calibration.reset()
                HarnessState.update { it.copy(calibrationProgress = null) }
                HarnessState.log("calibration samples discarded")
            }
            ACTION_MARK_FALSE_POSITIVE -> {
                metrics.onFalsePositive()
                store.saveOperatorCounters(metrics.snapshot().falsePositives, metrics.snapshot().missedCoughs)
                HarnessState.update { it.copy(metrics = metrics.snapshot()) }
                HarnessState.log("operator: false positive counted")
            }
            ACTION_MARK_MISSED -> {
                metrics.onMissedCough()
                store.saveOperatorCounters(metrics.snapshot().falsePositives, metrics.snapshot().missedCoughs)
                HarnessState.update { it.copy(metrics = metrics.snapshot()) }
                HarnessState.log("operator: missed cough counted")
            }
            ACTION_RESET_METRICS -> {
                metrics.reset()
                audio?.resetPeaks()
                store.saveOperatorCounters(0, 0)
                HarnessState.update { it.copy(nearMisses = emptyList()) }
                HarnessState.update { it.copy(metrics = metrics.snapshot()) }
                HarnessState.log("metrics reset")
            }
            ACTION_REFRESH_ROUTE -> refreshRoute()
            ACTION_ENSURE_RUNNING -> Unit
            else -> HarnessState.log("ignored intent: $action")
        }
    }

    private enum class MediaIntent { PLAY, PAUSE, TOGGLE }

    /**
     * One media button press. A single headset click usually arrives as PLAY_PAUSE (toggle),
     * a headset with two buttons sends explicit PLAY/PAUSE, so both paths must work.
     */
    private fun handleMedia(intent: MediaIntent) {
        val source = CommandSource.MEDIA_BUTTON
        val status = controller.snapshot().status
        val actions = when (intent) {
            MediaIntent.PLAY -> when (status) {
                PlaybackStatus.PAUSED -> controller.resume(source)
                PlaybackStatus.IDLE, PlaybackStatus.COMPLETED -> controller.start(source)
                PlaybackStatus.PLAYING -> emptyList()
            }
            MediaIntent.PAUSE -> when (status) {
                PlaybackStatus.PLAYING -> controller.pause(source)
                PlaybackStatus.PAUSED, PlaybackStatus.IDLE, PlaybackStatus.COMPLETED -> emptyList()
            }
            MediaIntent.TOGGLE -> when (status) {
                PlaybackStatus.PLAYING -> controller.pause(source)
                PlaybackStatus.PAUSED -> controller.resume(source)
                PlaybackStatus.IDLE, PlaybackStatus.COMPLETED -> controller.start(source)
            }
        }
        execute(actions)
        HarnessState.log("media button ${intent.name}: $status -> ${controller.snapshot().status}")
    }

    // ---------------------------------------------------------------- detection

    private fun onDetection(event: DetectionEvent) {
        val detectionAt = event.timestampMs
        handler.post {
            val wasPlaying = controller.snapshot().status == PlaybackStatus.PLAYING
            execute(controller.onCough(event.score, event.backend.name))
            val latency = SystemClock.elapsedRealtime() - detectionAt
            if (wasPlaying) {
                metrics.onCoughPause(latency)
                HarnessState.update { it.copy(metrics = metrics.snapshot()) }
                HarnessState.log(
                    "COUGH detected %.2f (%s) → paused %s in %d ms".format(
                        event.score,
                        event.backend.name,
                        controller.snapshot().checkpoint?.lineId ?: "?",
                        latency,
                    ),
                )
            } else {
                HarnessState.log("cough while ${controller.snapshot().status} — ignored")
            }
            HarnessState.onDetection(event, latency, accepted = wasPlaying)
        }
    }

    private fun onVoiceCommand(command: VoiceCommand, phrase: String) {
        handler.post {
            val before = controller.snapshot()
            when (command) {
                VoiceCommand.PAUSE -> {
                    execute(controller.pause(CommandSource.VOICE))
                    HarnessState.log("voice \"$phrase\" → pause")
                }
                VoiceCommand.PLAY, VoiceCommand.RESUME -> {
                    execute(controller.resume(CommandSource.VOICE, phrase))
                    HarnessState.log(
                        if (before.status == PlaybackStatus.PAUSED) {
                            "voice \"$phrase\" → resume line ${before.checkpoint?.lineId}"
                        } else {
                            "voice \"$phrase\" ignored (status=${before.status})"
                        },
                    )
                }
                VoiceCommand.STOP -> {
                    execute(controller.stop(CommandSource.VOICE))
                    HarnessState.log("voice \"$phrase\" → stop")
                }
            }
        }
    }

    // ---------------------------------------------------------------- TTS callbacks

    private fun dispatchTtsStart(utteranceId: String) {
        val (generation, index) = parseUtterance(utteranceId) ?: return
        execute(controller.onLineStarted(index, generation))
    }

    private fun dispatchTtsDone(utteranceId: String) {
        val (generation, index) = parseUtterance(utteranceId) ?: return
        execute(controller.onLineCompleted(index, generation))
    }

    private fun dispatchTtsError(utteranceId: String, code: Int) {
        val (generation, index) = parseUtterance(utteranceId) ?: return
        execute(controller.onLineFailed(index, generation, "tts error $code"))
    }

    private fun parseUtterance(id: String): Pair<Long, Int>? {
        val parts = id.split('|')
        if (parts.size != 2) return null
        val generation = parts[0].toLongOrNull() ?: return null
        val index = parts[1].toIntOrNull() ?: return null
        return generation to index
    }

    // ---------------------------------------------------------------- action execution

    private fun execute(actions: List<NarrationAction>) {
        for (action in actions) {
            when (action) {
                is NarrationAction.StopSpeech -> tts?.stop()
                is NarrationAction.SpeakLine -> {
                    if (tts?.ready != true) {
                        HarnessState.log("TTS not ready — queued ${action.lineId} until the engine reports ready")
                        ttsQueued = false
                        continue
                    }
                    ttsQueued = true
                    tts?.speak(action.text, "${action.generation}|${action.lineIndex}")
                }
                is NarrationAction.Persist -> {
                    store.saveSession(controller.exportSession(System.currentTimeMillis()))
                }
                is NarrationAction.Publish -> publish(action.snapshot)
            }
        }
    }

    private fun publish(snapshot: NarrationSnapshot) {
        if (lastPolicyStatus != snapshot.status) {
            applyMicPolicy(snapshot.status)
        }
        HarnessState.update {
            it.copy(
                narration = snapshot,
                serviceRunning = running,
                metrics = metrics.snapshot(),
                settings = settings,
            )
        }
        updateNotification(snapshot)
        updateMediaSession(snapshot)
    }

    // ---------------------------------------------------------------- calibration

    private fun startCalibrationPhase(phase: String, durationMs: Int) {
        if (audio == null) {
            HarnessState.setError("Calibration needs the detector: narrate or stop, then capture again")
            HarnessState.log("calibration refused: recogniser currently owns the microphone")
            return
        }
        calibration.begin(phase, SystemClock.elapsedRealtime() + durationMs)
        HarnessState.update { it.copy(calibrationProgress = calibration.describe()) }
        HarnessState.log("calibration: capturing $phase for ${durationMs}ms")
    }

    private fun collectCalibrationSample(dsp: DspResult, yamnetScore: Float, accepted: Boolean) {
        if (!calibration.isActive()) return
        val now = SystemClock.elapsedRealtime()
        if (now > calibration.deadline) {
            calibration.endPhase()
            HarnessState.update { it.copy(calibrationProgress = calibration.describe()) }
            HarnessState.log("calibration: ${calibration.describe()}")
            return
        }
        calibration.add(dsp.score, yamnetScore)
        if (calibration.shouldRefreshUi()) {
            HarnessState.update { it.copy(calibrationProgress = calibration.describe()) }
        }
    }

    private fun applyCalibration() {
        val coughDsp = calibration.coughDsp
        val negDsp = calibration.negativeDsp
        if (coughDsp.size < 3 || negDsp.size < 3) {
            HarnessState.setError("Calibration needs at least 3 cough and 3 background windows")
            return
        }
        val now = System.currentTimeMillis()
        val dspResult = Calibrator.compute(coughDsp, negDsp, 0.10f, now)
        val yamnetResult = Calibrator.compute(calibration.coughYamnet, calibration.negativeYamnet, 0.10f, now)
        settings = settings.copy(
            dspThreshold = dspResult.threshold,
            yamnetThreshold = yamnetResult.threshold,
            calibrateGain = 0f,
        )
        store.saveSettings(settings)
        store.saveCalibration(dspResult)
        audio?.resetGate()
        HarnessState.update {
            it.copy(settings = settings, calibration = dspResult, calibrationProgress = calibration.describe())
        }
        HarnessState.log(
            "calibration applied: dsp=%.2f yamnet=%.2f (tpr=%.0f%% fpr=%.0f%%)".format(
                dspResult.threshold,
                yamnetResult.threshold,
                dspResult.truePositiveRate * 100,
                dspResult.falsePositiveRate * 100,
            ),
        )
    }

    private inner class CalibrationSession {
        val coughDsp = mutableListOf<Float>()
        val negativeDsp = mutableListOf<Float>()
        val coughYamnet = mutableListOf<Float>()
        val negativeYamnet = mutableListOf<Float>()
        private var phase: String? = null
        var deadline: Long = 0L
        private var lastUiRefresh = 0L

        fun begin(phase: String, deadlineMs: Long) {
            this.phase = phase
            this.deadline = deadlineMs
        }

        fun isActive(): Boolean = phase != null

        fun endPhase() {
            phase = null
            deadline = 0L
        }

        fun add(dspScore: Float, yamnetScore: Float) {
            when (phase) {
                "cough" -> {
                    coughDsp += dspScore
                    coughYamnet += yamnetScore
                }
                "background" -> {
                    negativeDsp += dspScore
                    negativeYamnet += yamnetScore
                }
            }
        }

        fun shouldRefreshUi(): Boolean {
            val now = SystemClock.elapsedRealtime()
            if (now - lastUiRefresh < 400) return false
            lastUiRefresh = now
            return true
        }

        fun describe(): String =
            "cough windows: ${coughDsp.size} · background windows: ${negativeDsp.size}" +
                if (phase != null) " · capturing $phase" else ""

        fun reset() {
            coughDsp.clear()
            negativeDsp.clear()
            coughYamnet.clear()
            negativeYamnet.clear()
            endPhase()
        }
    }

    // ---------------------------------------------------------------- notification / session

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Narration harness",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Cough-to-pause narration status"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(snapshot: NarrationSnapshot): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val toggleIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, NarrationService::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, NarrationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val playing = snapshot.status == PlaybackStatus.PLAYING
        val paused = snapshot.status == PlaybackStatus.PAUSED
        val text = when {
            paused -> "Paused at ${snapshot.checkpoint?.lineId ?: "?"} — say “Play” to resume"
            playing -> "Narrating ${snapshot.currentLineId ?: "?"}: ${snapshot.currentLineText?.take(48) ?: ""}…"
            else -> "Idle — tap Start in the app"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_narration)
            .setContentTitle("Cough-to-Pause")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .addAction(0, if (paused) "Resume" else "Pause", toggleIntent)
            .addAction(0, "Stop", stopIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(snapshot: NarrationSnapshot) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (!startedForeground) return
        runCatching { nm.notify(NOTIFICATION_ID, buildNotification(snapshot)) }
    }

    private fun startForegroundSafely() {
        if (startedForeground) return
        val notification = buildNotification(controller.snapshot())
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, if (micGranted) type else 0)
            startedForeground = true
        } catch (e: Throwable) {
            HarnessState.setError("Foreground service failed: ${e.javaClass.simpleName}: ${e.message}")
            runCatching { startForeground(NOTIFICATION_ID, notification) }
            startedForeground = true
        }
    }

    private fun updateMediaSession(snapshot: NarrationSnapshot) {
        val session = mediaSession ?: return
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_STOP or PlaybackState.ACTION_PLAY_PAUSE
        val state = when (snapshot.status) {
            PlaybackStatus.PLAYING -> PlaybackState.STATE_PLAYING
            PlaybackStatus.PAUSED -> PlaybackState.STATE_PAUSED
            else -> PlaybackState.STATE_NONE
        }
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(state, snapshot.currentLineIndex.toLong(), 1f)
                .build(),
        )
        session.setMetadata(
            android.media.MediaMetadata.Builder()
                .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, snapshot.currentLineId ?: "idle")
                .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, "cough-to-pause harness")
                .build(),
        )
    }

    // ---------------------------------------------------------------- wake lock / battery / route

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cough_to_pause:mic").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.release() }
        wakeLock = null
    }

    private fun registerScreenReceiver() {
        val pm = getSystemService(PowerManager::class.java)
        HarnessState.update { it.copy(screenOn = pm?.isInteractive ?: true) }
        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val on = intent.action == Intent.ACTION_SCREEN_ON
            HarnessState.update { it.copy(screenOn = on) }
            HarnessState.log(
                "screen ${if (on) "ON" else "OFF"} · analysed windows=$captureWindowsSeen" +
                    (audio?.let { " · silent reads=${it.zeroReads}" } ?: " · detector not owning mic"),
            )
        }
    }

    /**
     * Capture watchdog. A foreground service with the microphone type is allowed to record with
     * the screen off, but if the platform or an OEM policy silences the input, AudioRecord keeps
     * returning zeroed buffers instead of failing. Bumping the microphone back to life is the
     * difference between "the detector missed it" and "nothing was ever recorded".
     */
    private val captureWatchdog = object : Runnable {
        override fun run() {
            val engine = audio
            if (engine != null) {
                if (engine !== lastWatchdogEngine) {
                    lastWatchdogEngine = engine
                    lastWatchdogWindows = -1
                }
                val windows = engine.windowsAnalysed
                captureWindowsSeen = windows
                if (windows == lastWatchdogWindows) {
                    HarnessState.log(
                        "capture stalled: no analysis window in 10 s (silent reads=${engine.zeroReads}, " +
                            "read errors=${engine.readErrors}) — restarting microphone",
                    )
                    micExecutor.execute {
                        setDetectorWanted(false)
                        setDetectorWanted(true)
                    }
                }
                lastWatchdogWindows = windows
                val nearMisses = engine.nearMissSnapshot()
                HarnessState.update { it.copy(nearMisses = nearMisses) }
                HarnessState.log(
                    "capture: windows=$windows · silent reads=${engine.zeroReads} · errs=${engine.readErrors} · " +
                        "peak %.0f dB · screen=${if (HarnessState.state.value.screenOn) "on" else "off"}"
                            .format(engine.lastPeakDb),
                )
            }
            handler.postDelayed(this, 10_000)
        }
    }

    private fun scheduleBatterySample() {
        handler.postDelayed(batteryRunnable, 15_000)
    }

    private val batteryRunnable = object : Runnable {
        override fun run() {
            val sample = battery?.sample()
            if (sample != null) HarnessState.update { it.copy(battery = sample) }
            refreshRoute()
            handler.postDelayed(this, 60_000)
        }
    }

    private fun refreshRoute() {
        val monitor = routeMonitor ?: return
        val engine = audio
        val info = monitor.snapshot(
            recordRateHz = engine?.capturedSampleRate ?: CoughAudioEngine.SAMPLE_RATE,
            aecRequested = engine?.aecRequested ?: false,
            aecActive = engine?.aecActive ?: false,
        )
        HarnessState.update { it.copy(route = info) }
    }

    companion object {
        const val ACTION_START_NARRATION = "dev.harness.coughtopause.START_NARRATION"
        const val ACTION_RESUME = "dev.harness.coughtopause.RESUME"
        const val ACTION_PAUSE = "dev.harness.coughtopause.PAUSE"
        const val ACTION_STOP = "dev.harness.coughtopause.STOP"
        const val ACTION_TOGGLE = "dev.harness.coughtopause.TOGGLE"
        const val ACTION_RELOAD_SETTINGS = "dev.harness.coughtopause.RELOAD_SETTINGS"
        const val ACTION_CALIBRATE_PHASE = "dev.harness.coughtopause.CALIBRATE_PHASE"
        const val ACTION_CALIBRATE_APPLY = "dev.harness.coughtopause.CALIBRATE_APPLY"
        const val ACTION_CALIBRATE_CLEAR = "dev.harness.coughtopause.CALIBRATE_CLEAR"
        const val ACTION_MARK_FALSE_POSITIVE = "dev.harness.coughtopause.MARK_FALSE_POSITIVE"
        const val ACTION_MARK_MISSED = "dev.harness.coughtopause.MARK_MISSED"
        const val ACTION_RESET_METRICS = "dev.harness.coughtopause.RESET_METRICS"
        const val ACTION_REFRESH_ROUTE = "dev.harness.coughtopause.REFRESH_ROUTE"
        const val ACTION_ENSURE_RUNNING = "dev.harness.coughtopause.ENSURE_RUNNING"
        const val ACTION_NARRATE_SCENE = "dev.harness.coughtopause.NARRATE_SCENE"
        const val EXTRA_SCENE_ID = "scene_id"
        const val EXTRA_LINES = "scene_lines"

        const val EXTRA_PHASE = "phase"
        const val EXTRA_DURATION_MS = "duration_ms"

        private const val CHANNEL_ID = "narration"
        private const val NOTIFICATION_ID = 42

        fun send(context: Context, action: String, phase: String? = null, durationMs: Int = 4000) {
            val intent = Intent(context, NarrationService::class.java).setAction(action)
            if (phase != null) intent.putExtra(EXTRA_PHASE, phase)
            intent.putExtra(EXTRA_DURATION_MS, durationMs)
            ContextCompat.startForegroundService(context, intent)
        }

        fun startNarration(context: Context) = send(context, ACTION_START_NARRATION)

        /** Narrate an RPG scene through the same checkpoint engine as the harness script. */
        fun narrateScene(context: Context, sceneId: String, lines: List<String>) {
            val intent = Intent(context, NarrationService::class.java).setAction(ACTION_NARRATE_SCENE)
            intent.putExtra(EXTRA_SCENE_ID, sceneId)
            intent.putStringArrayListExtra(EXTRA_LINES, ArrayList(lines))
            ContextCompat.startForegroundService(context, intent)
        }
        fun resume(context: Context) = send(context, ACTION_RESUME)
        fun pause(context: Context) = send(context, ACTION_PAUSE)
        fun stopNarration(context: Context) = send(context, ACTION_STOP)
        fun ensureRunning(context: Context) = send(context, ACTION_ENSURE_RUNNING)
        fun reloadSettings(context: Context) = send(context, ACTION_RELOAD_SETTINGS)
        fun markFalsePositive(context: Context) = send(context, ACTION_MARK_FALSE_POSITIVE)
        fun markMissed(context: Context) = send(context, ACTION_MARK_MISSED)
        fun resetMetrics(context: Context) = send(context, ACTION_RESET_METRICS)
        fun calibrate(context: Context, phase: String, durationMs: Int = 4000) =
            send(context, ACTION_CALIBRATE_PHASE, phase, durationMs)
        fun applyCalibration(context: Context) = send(context, ACTION_CALIBRATE_APPLY)
        fun clearCalibration(context: Context) = send(context, ACTION_CALIBRATE_CLEAR)

        /** Media button receiver entry point: bring the service up already toggled. */
        fun mediaButton(context: Context) = send(context, ACTION_TOGGLE)
    }
}

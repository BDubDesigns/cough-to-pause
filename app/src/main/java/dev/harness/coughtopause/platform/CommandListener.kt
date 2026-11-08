package dev.harness.coughtopause.platform

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dev.harness.coughtopause.core.VoiceCommand
import dev.harness.coughtopause.core.VoiceCommands

/**
 * Continuous recognition of the restricted command vocabulary.
 *
 * Uses the platform on-device recogniser where available (`createOnDeviceSpeechRecognizer`,
 * API 31+) with `EXTRA_PREFER_OFFLINE`, and biasing strings (API 33+) to pull the recogniser
 * toward four words. No audio leaves the device through this class, and no network is used by
 * the app itself; if the device has no offline English model the harness reports that plainly
 * instead of silently falling back to a cloud recogniser.
 */
class CommandListener(
    private val context: Context,
    private val onCommand: (VoiceCommand, String) -> Unit,
    private val onStatus: (VoiceStatus, String?) -> Unit,
) {

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var running = false

    /**
     * On-device recognisers sometimes stop delivering callbacks after a long silence without
     * raising an error. A watchdog rebuilds the session, which is cheaper than a dead command
     * channel during a playtest.
     */
    private val watchdog = Runnable {
        if (running) {
            HarnessState.log("voice: watchdog restart (no callback for ${WATCHDOG_MS}ms)")
            createRecognizer()
        }
    }

    fun start() {
        if (running) return
        running = true
        onStatus(VoiceStatus.STARTING, null)
        createRecognizer()
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        runCatching { recognizer?.destroy() }
        recognizer = null
        onStatus(VoiceStatus.OFF, null)
    }

    private fun createRecognizer() {
        if (!running) return
        runCatching { recognizer?.destroy() }
        recognizer = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            ) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
        } catch (e: Throwable) {
            onStatus(VoiceStatus.UNAVAILABLE, "recogniser unavailable: ${e.message}")
            return
        }
        if (recognizer == null) {
            onStatus(VoiceStatus.UNAVAILABLE, "no speech recogniser on this device")
            return
        }
        recognizer?.setRecognitionListener(listener)
        listen()
    }

    private fun listen() {
        if (!running) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                putStringArrayListExtra(
                    RecognizerIntent.EXTRA_BIASING_STRINGS,
                    ArrayList(VoiceCommands.vocabulary),
                )
            }
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 500L)
        }
        runCatching { recognizer?.startListening(intent) }
            .onFailure { onStatus(VoiceStatus.RETRYING, it.message) }
        HarnessState.log("voice: startListening (${recognizer?.javaClass?.simpleName})")
        armWatchdog()
    }

    private fun armWatchdog() {
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, WATCHDOG_MS)
    }

    private fun handleText(text: String?) {
        if (text.isNullOrBlank()) return
        HarnessState.update { it.copy(voiceLastHeard = text) }
        val command = VoiceCommands.parse(text) ?: return
        onCommand(command, text)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            onStatus(VoiceStatus.LISTENING, null)
            HarnessState.log("voice: ready for speech")
            armWatchdog()
        }

        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit

        override fun onError(error: Int) {
            handler.removeCallbacks(watchdog)
            HarnessState.log("voice: error $error")
            val detail = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "no match"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "speech timeout"
                SpeechRecognizer.ERROR_AUDIO -> "audio error"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "microphone permission"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recognizer busy"
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                    "offline model missing, network blocked"
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "language not supported"
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "language not downloaded"
                SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "server disconnected"
                SpeechRecognizer.ERROR_CLIENT -> "client error"
                else -> "error $error"
            }
            val unavailable = error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ||
                error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
            onStatus(if (unavailable) VoiceStatus.UNAVAILABLE else VoiceStatus.RETRYING, detail)
            if (!running) return
            // A silent window is normal: keep the same session and listen again immediately.
            // Rebuilding the session costs ~0.5 s and caused the server-disconnect churn seen
            // during the first playtest, so only real failures recreate it.
            if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                handler.postDelayed({ listen() }, 120)
            } else {
                handler.postDelayed({ createRecognizer() }, 500)
            }
        }

        override fun onResults(results: Bundle?) {
            handler.removeCallbacks(watchdog)
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            list?.forEach { handleText(it) }
            HarnessState.log("voice: results ${list?.take(3)}")
            HarnessState.update { it.copy(voiceStatus = VoiceStatus.LISTENING) }
            if (running) handler.postDelayed({ listen() }, 120)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            // Partial results are what make a spoken "Pause" feel immediate.
            val list = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            list?.forEach { handleText(it) }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private companion object {
        const val WATCHDOG_MS = 10_000L
    }
}

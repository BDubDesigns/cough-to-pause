package dev.harness.coughtopause.platform

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * TextToSpeech wrapper: one line per utterance, callbacks tagged with the generation that
 * produced them so the state machine can discard stale callbacks from an aborted line.
 *
 * Utterance ids are "generation|lineIndex"; the service parses them back into both values.
 */
class TtsNarrator(
    context: Context,
    private val onInit: (ready: Boolean, detail: String) -> Unit,
    private val onStart: (utteranceId: String) -> Unit,
    private val onDone: (utteranceId: String) -> Unit,
    private val onError: (utteranceId: String, code: Int) -> Unit,
) {

    private var tts: TextToSpeech? = null

    @Volatile
    var ready: Boolean = false
        private set

    @Volatile
    var voiceName: String = "unknown"
        private set

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            val engine = tts
            if (status == TextToSpeech.SUCCESS && engine != null) {
                val language = engine.setLanguage(Locale.US)
                engine.setSpeechRate(0.98f)
                engine.setPitch(1.0f)
                engine.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                voiceName = engine.defaultVoice?.name ?: "default"
                ready = language != TextToSpeech.LANG_MISSING_DATA &&
                    language != TextToSpeech.LANG_NOT_SUPPORTED
                onInit(
                    ready,
                    if (ready) "voice=$voiceName" else "English voice data missing on this device",
                )
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        utteranceId?.let(onStart)
                    }

                    override fun onDone(utteranceId: String?) {
                        utteranceId?.let(onDone)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        utteranceId?.let { onError(it, TextToSpeech.ERROR) }
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        utteranceId?.let { onError(it, errorCode) }
                    }

                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        HarnessState.log("tts stop ${utteranceId ?: "-"} interrupted=$interrupted")
                    }
                })
            } else {
                ready = false
                onInit(false, "TextToSpeech init failed (status=$status)")
            }
        }
    }

    /** Speak one line, flushing anything already queued. */
    fun speak(text: String, utteranceId: String) {
        HarnessState.log("tts speak $utteranceId (${text.length} chars)")
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), utteranceId)
    }

    fun stop() {
        tts?.stop()
    }

    fun isSpeaking(): Boolean = tts?.isSpeaking == true

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}

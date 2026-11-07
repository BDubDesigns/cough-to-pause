package dev.harness.coughtopause.platform

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/** AudioSet classes that matter for this experiment (YAMNet class map indices). */
object YamnetClasses {
    const val SPEECH = 0
    const val LAUGHTER = 13
    const val COUGH = 42
    const val THROAT_CLEAR = 43
    const val SNEEZE = 44
    const val SNIFF = 45
    const val SPEECH_SYNTH = 5
    const val BACKGROUND_NOISE = 503
}

data class YamnetResult(
    val cough: Float,
    val throatClear: Float,
    val speech: Float,
    val laughter: Float,
    val sneeze: Float,
    val combined: Float,
    val speechSuppressed: Boolean,
)

/**
 * YAMNet (AudioSet, 521 classes) through TensorFlow Lite.
 *
 * Input/output shapes are read from the model at load time rather than hard-coded: the harness
 * has to survive being pointed at a differently exported YAMNet (or a replacement model) without
 * a rebuild. Inference runs on the audio analysis thread; the interpreter is single threaded.
 */
class YamnetClassifier(private val context: Context) {

    private var interpreter: Interpreter? = null
    private var inputElements: Int = 0
    private var outputElements: Int = 0
    private var inputIs2D: Boolean = false
    private var outputIs2D: Boolean = false

    @Volatile
    var status: String = "not loaded"
        private set

    @Volatile
    var loaded: Boolean = false
        private set

    /** Wall time of the most recent inference, for the diagnostics screen. */
    @Volatile
    var lastInferenceMs: Long = 0L
        private set

    fun load(): Boolean {
        if (loaded) return true
        return try {
            val model = assetModel()
            val options = Interpreter.Options().apply { setNumThreads(2) }
            val itp = Interpreter(model, options)
            val inputShape = itp.getInputTensor(0).shape()
            val outputShape = itp.getOutputTensor(0).shape()
            inputElements = inputShape.fold(1) { acc, v -> acc * v }
            outputElements = outputShape.fold(1) { acc, v -> acc * v }
            inputIs2D = inputShape.size >= 2
            outputIs2D = outputShape.size >= 2
            interpreter = itp
            loaded = true
            status = "YAMNet ok · in ${inputShape.toList()} out ${outputShape.toList()}"
            status
            true
        } catch (e: Throwable) {
            loaded = false
            status = "YAMNet unavailable: ${e.javaClass.simpleName}: ${e.message}"
            false
        }
    }

    val inputSampleCount: Int
        get() = if (inputElements > 0) inputElements else 15_600

    /** Returns null when the model is not loaded; callers then fall back to DSP only. */
    fun classify(window: FloatArray): YamnetResult? {
        val itp = interpreter ?: return null
        if (inputElements <= 0 || outputElements < 521) return null
        return try {
            val input = ByteBuffer
                .allocateDirect(inputElements * 4)
                .order(ByteOrder.nativeOrder())
            val count = minOf(inputElements, window.size)
            val start = (window.size - count).coerceAtLeast(0)
            for (i in 0 until count) {
                input.putFloat(window[start + i])
            }
            for (i in count until inputElements) input.putFloat(0f)
            input.rewind()

            val outShape = if (outputIs2D) intArrayOf(1, outputElements) else intArrayOf(outputElements)
            val out = Array(1) { FloatArray(outputElements) }
            val outputs = if (outputIs2D) mapOf(0 to out) else mapOf(0 to out[0])
            val startedAt = android.os.SystemClock.elapsedRealtimeNanos()
            itp.runForMultipleInputsOutputs(arrayOf(input), outputs)
            lastInferenceMs = (android.os.SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000_000L
            val scores = out[0]
            val cough = scores[YamnetClasses.COUGH]
            val throat = scores[YamnetClasses.THROAT_CLEAR]
            val speech = scores[YamnetClasses.SPEECH]
            val laughter = scores[YamnetClasses.LAUGHTER]
            val sneeze = scores[YamnetClasses.SNEEZE]
            // Narration through the phone speaker is itself speech: suppress the obvious case.
            val speechSuppressed = speech > 0.55f && cough < 0.20f
            val combined = maxOf(cough, throat * 0.7f, sneeze * 0.5f)
                .let { if (speechSuppressed) it * 0.6f else it }
            YamnetResult(cough, throat, speech, laughter, sneeze, combined, speechSuppressed)
        } catch (e: Throwable) {
            status = "YAMNet inference failed: ${e.message}"
            null
        }
    }

    fun close() {
        interpreter?.close()
        interpreter = null
        loaded = false
    }

    private fun assetModel(): MappedByteBuffer {
        val afd = context.assets.openFd("yamnet.tflite")
        FileInputStream(afd.fileDescriptor).use { stream ->
            return stream.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
        }
    }
}

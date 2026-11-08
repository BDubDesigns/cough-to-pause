package dev.harness.coughtopause.core

/** Where a command came from. Kept in the snapshot for the diagnostics screen. */
enum class CommandSource {
    COUGH_DETECTOR,
    VOICE,
    UI,
    MEDIA_BUTTON,
    SYSTEM,
}

enum class PlaybackStatus {
    /** Nothing loaded, nothing speaking. */
    IDLE,

    /** A line is being spoken. */
    PLAYING,

    /** Narration interrupted, checkpoint saved, waiting for resume. */
    PAUSED,

    /** The whole script has been narrated. */
    COMPLETED,
}

enum class PauseReason {
    COUGH,
    VOICE,
    MEDIA_BUTTON,
    UI,
    RESTORED,
}

/** The interrupted line, kept across pause, process death and app restart. */
data class Checkpoint(
    val lineIndex: Int,
    val lineId: String,
    val reason: PauseReason,
    val capturedAtMs: Long,
)

data class Counters(
    val coughPauses: Int = 0,
    val voicePauses: Int = 0,
    val manualPauses: Int = 0,
    val resumes: Int = 0,
    val ignoredCoughs: Int = 0,
    val completedLines: Int = 0,
    val staleCallbacks: Int = 0,
)

data class LastEvent(
    val kind: String,
    val detail: String,
    val atMs: Long,
    val confidence: Float = Float.NaN,
)

data class NarrationSnapshot(
    val scriptId: String,
    val status: PlaybackStatus,
    val currentLineIndex: Int,
    val currentLineId: String?,
    val checkpoint: Checkpoint?,
    val generation: Long,
    val counters: Counters,
    val lastEvent: LastEvent?,
) {
    val currentLineText: String?
        get() = NarrationScript.lineAt(currentLineIndex)?.text
}

/** Commands the state machine emits for the Android layer to execute. */
sealed interface NarrationAction {
    data class SpeakLine(
        val lineIndex: Int,
        val lineId: String,
        val text: String,
        val restart: Boolean,
        val generation: Long,
    ) : NarrationAction

    data object StopSpeech : NarrationAction

    data class Persist(val snapshot: NarrationSnapshot) : NarrationAction

    data class Publish(val snapshot: NarrationSnapshot) : NarrationAction
}

/** Restored session, as persisted by the Android layer. */
data class PersistedSession(
    val scriptId: String,
    val status: PlaybackStatus,
    val lineIndex: Int,
    val lineId: String?,
    val reason: PauseReason?,
    val pausedAtMs: Long,
    val counters: Counters,
    val updatedAtEpochMs: Long,
)

/**
 * The narration state machine. Pure Kotlin: no Android types, no threads of its own, no clock.
 * Every public method returns the ordered list of actions the caller must execute, which makes
 * the ordering and race behaviour directly testable.
 *
 * Invariants:
 *  - PAUSED always has a checkpoint whose line index is the line that was interrupted.
 *  - Resuming restarts the interrupted line from its beginning; earlier lines are never replayed.
 *  - A cough while PAUSED or IDLE never moves the checkpoint.
 *  - Line callbacks carrying a stale generation are ignored (they belong to an aborted utterance).
 */
class NarrationController(
    script: List<ScriptLine> = NarrationScript.LINES,
    scriptId: String = NarrationScript.SCRIPT_ID,
    private val now: () -> Long = SystemClockLike::elapsed,
) {

    private var script: List<ScriptLine> = script
    private var scriptId: String = scriptId

    private var status: PlaybackStatus = PlaybackStatus.IDLE
    private var currentLineIndex: Int = -1
    private var checkpoint: Checkpoint? = null
    private var generation: Long = 0
    private var counters: Counters = Counters()
    private var lastEvent: LastEvent? = null

    /**
     * Swap the narration source (the M0 harness script, or an RPG scene) without changing the
     * pause/resume contract: the checkpoint is cleared and the next [start] speaks line 0.
     */
    fun loadScript(newScriptId: String, lines: List<ScriptLine>): List<NarrationAction> {
        val actions = mutableListOf<NarrationAction>()
        actions += NarrationAction.StopSpeech
        script = lines
        scriptId = newScriptId
        status = PlaybackStatus.IDLE
        currentLineIndex = -1
        checkpoint = null
        generation += 1
        lastEvent = LastEvent("script-loaded", "id=$newScriptId lines=${lines.size}", now())
        return commit(actions)
    }

    fun snapshot(): NarrationSnapshot = NarrationSnapshot(
        scriptId = scriptId,
        status = status,
        currentLineIndex = currentLineIndex,
        currentLineId = script.getOrNull(currentLineIndex)?.id,
        checkpoint = checkpoint,
        generation = generation,
        counters = counters,
        lastEvent = lastEvent,
    )

    /**
     * Start narration from the top of the script. Always line 0, always a clean checkpoint,
     * because "start" is an explicit operator intent rather than a resume.
     */
    fun start(source: CommandSource = CommandSource.UI): List<NarrationAction> {
        val actions = mutableListOf<NarrationAction>()
        if (script.isEmpty()) {
            status = PlaybackStatus.COMPLETED
            return commit(actions)
        }
        actions += NarrationAction.StopSpeech
        checkpoint = null
        status = PlaybackStatus.PLAYING
        currentLineIndex = 0
        counters = counters.copy(completedLines = 0)
        lastEvent = LastEvent("start", "source=${source.name}", now())
        actions += speak(0, restart = true)
        return commit(actions)
    }

    /** Operator or voice pause while playing. */
    fun pause(source: CommandSource): List<NarrationAction> {
        val actions = mutableListOf<NarrationAction>()
        when (status) {
            PlaybackStatus.PLAYING -> actions += enterPaused(source, reasonFor(source))
            PlaybackStatus.PAUSED, PlaybackStatus.IDLE, PlaybackStatus.COMPLETED ->
                lastEvent = LastEvent("pause-ignored", "status=${status.name} source=${source.name}", now())
        }
        return commit(actions)
    }

    /**
     * Resume from the checkpoint. The interrupted line is spoken again from its start.
     * No-op unless PAUSED with a valid checkpoint.
     */
    fun resume(source: CommandSource, phrase: String? = null): List<NarrationAction> {
        val actions = mutableListOf<NarrationAction>()
        val cp = checkpoint
        if (status != PlaybackStatus.PAUSED || cp == null) {
            lastEvent = LastEvent("resume-ignored", "status=${status.name} source=${source.name}", now())
            return commit(actions)
        }
        if (script.getOrNull(cp.lineIndex)?.id != cp.lineId) {
            // Script changed underneath the checkpoint: fall back to the script id recorded then.
            lastEvent = LastEvent("resume-ignored", "checkpoint-line-missing", now())
            return commit(actions)
        }
        actions += NarrationAction.StopSpeech
        status = PlaybackStatus.PLAYING
        currentLineIndex = cp.lineIndex
        counters = counters.copy(resumes = counters.resumes + 1)
        lastEvent = LastEvent(
            "resume",
            buildString {
                append("source=").append(source.name)
                if (phrase != null) append(" phrase=").append(phrase)
                append(" line=").append(cp.lineId)
            },
            now(),
        )
        checkpoint = null
        actions += speak(cp.lineIndex, restart = true)
        return commit(actions)
    }

    /** Cough detected while playing: immediate pause with the current line as checkpoint. */
    fun onCough(confidence: Float, backend: String): List<NarrationAction> {
        val actions = mutableListOf<NarrationAction>()
        when (status) {
            PlaybackStatus.PLAYING -> {
                val idx = if (currentLineIndex >= 0) currentLineIndex else 0
                actions += enterPaused(
                    CommandSource.COUGH_DETECTOR,
                    PauseReason.COUGH,
                    confidence = confidence,
                    detail = "backend=$backend line=${script.getOrNull(idx)?.id}",
                )
                counters = counters.copy(coughPauses = counters.coughPauses + 1)
            }
            PlaybackStatus.PAUSED, PlaybackStatus.IDLE, PlaybackStatus.COMPLETED -> {
                counters = counters.copy(ignoredCoughs = counters.ignoredCoughs + 1)
                lastEvent = LastEvent(
                    "cough-ignored",
                    "status=${status.name} confidence=%.2f".format(confidence),
                    now(),
                    confidence,
                )
            }
        }
        return commit(actions)
    }

    /** TTS reports that it started speaking utterance [generation]. */
    fun onLineStarted(index: Int, lineGeneration: Long): List<NarrationAction> {
        val actions = mutableListOf<NarrationAction>()
        if (lineGeneration != generation) {
            counters = counters.copy(staleCallbacks = counters.staleCallbacks + 1)
            lastEvent = LastEvent("stale-start", "gen=$lineGeneration current=$generation", now())
        } else if (status == PlaybackStatus.PLAYING) {
            currentLineIndex = index
        }
        return commit(actions)
    }

    /** TTS reports the line finished. Only the current generation may advance the script. */
    fun onLineCompleted(index: Int, lineGeneration: Long): List<NarrationAction> {
        val actions = mutableListOf<NarrationAction>()
        if (lineGeneration != generation) {
            counters = counters.copy(staleCallbacks = counters.staleCallbacks + 1)
            lastEvent = LastEvent("stale-complete", "gen=$lineGeneration current=$generation", now())
            return commit(actions)
        }
        if (status != PlaybackStatus.PLAYING) {
            return commit(actions)
        }
        counters = counters.copy(completedLines = counters.completedLines + 1)
        val next = index + 1
        if (next < script.size) {
            currentLineIndex = next
            lastEvent = LastEvent("line-completed", "line=${script[index].id} next=${script[next].id}", now())
            actions += speak(next, restart = false)
        } else {
            status = PlaybackStatus.COMPLETED
            currentLineIndex = script.lastIndex
            lastEvent = LastEvent("script-completed", "lines=${script.size}", now())
            actions += NarrationAction.StopSpeech
        }
        return commit(actions)
    }

    /** TTS failed on a line. Skip it rather than looping forever. */
    fun onLineFailed(index: Int, lineGeneration: Long, message: String): List<NarrationAction> {
        val actions = mutableListOf<NarrationAction>()
        if (lineGeneration != generation) {
            counters = counters.copy(staleCallbacks = counters.staleCallbacks + 1)
            return commit(actions)
        }
        lastEvent = LastEvent("line-failed", "line=$index $message", now())
        val next = index + 1
        if (next < script.size && status == PlaybackStatus.PLAYING) {
            currentLineIndex = next
            actions += speak(next, restart = true)
        }
        return commit(actions)
    }

    fun stop(source: CommandSource): List<NarrationAction> {
        val actions = mutableListOf<NarrationAction>()
        actions += NarrationAction.StopSpeech
        status = PlaybackStatus.IDLE
        checkpoint = null
        currentLineIndex = -1
        lastEvent = LastEvent("stop", "source=${source.name}", now())
        return commit(actions)
    }

    /**
     * Restore after process death. A session that was PLAYING cannot be resumed mid-utterance,
     * so it is restored as PAUSED at that line: state survives, audio does not surprise anyone.
     */
    fun restore(session: PersistedSession?): List<NarrationAction> {
        val actions = mutableListOf<NarrationAction>()
        if (session == null || session.scriptId != scriptId) {
            return actions
        }
        counters = session.counters
        if (session.status == PlaybackStatus.PAUSED) {
            val line = script.getOrNull(session.lineIndex) ?: return actions
            checkpoint = Checkpoint(
                lineIndex = session.lineIndex,
                lineId = session.lineId ?: line.id,
                reason = session.reason ?: PauseReason.RESTORED,
                capturedAtMs = session.pausedAtMs,
            )
            status = PlaybackStatus.PAUSED
            currentLineIndex = session.lineIndex
            generation += 1
            lastEvent = LastEvent("restored", "line=${checkpoint?.lineId} reason=${session.reason}", now())
        } else if (session.status == PlaybackStatus.PLAYING) {
            val line = script.getOrNull(session.lineIndex) ?: return actions
            checkpoint = Checkpoint(
                lineIndex = session.lineIndex,
                lineId = line.id,
                reason = PauseReason.RESTORED,
                capturedAtMs = session.pausedAtMs,
            )
            status = PlaybackStatus.PAUSED
            currentLineIndex = session.lineIndex
            generation += 1
            lastEvent = LastEvent("restored-paused", "line=${line.id} was-playing", now())
        } else {
            status = session.status
            currentLineIndex = session.lineIndex
        }
        return commit(actions)
    }

    fun exportSession(epochNow: Long): PersistedSession = PersistedSession(
        scriptId = scriptId,
        status = status,
        lineIndex = currentLineIndex,
        lineId = script.getOrNull(currentLineIndex)?.id,
        reason = checkpoint?.reason ?: if (status == PlaybackStatus.PAUSED) PauseReason.RESTORED else null,
        pausedAtMs = checkpoint?.capturedAtMs ?: now(),
        counters = counters,
        updatedAtEpochMs = epochNow,
    )

    private fun enterPaused(
        source: CommandSource,
        reason: PauseReason,
        confidence: Float = Float.NaN,
        detail: String? = null,
    ): NarrationAction {
        val idx = if (currentLineIndex >= 0) currentLineIndex else 0
        val line = script.getOrNull(idx)
        status = PlaybackStatus.PAUSED
        currentLineIndex = idx
        checkpoint = Checkpoint(
            lineIndex = idx,
            lineId = line?.id ?: "L??",
            reason = reason,
            capturedAtMs = now(),
        )
        when (reason) {
            PauseReason.COUGH, PauseReason.RESTORED -> Unit
            PauseReason.VOICE -> counters = counters.copy(voicePauses = counters.voicePauses + 1)
            PauseReason.MEDIA_BUTTON, PauseReason.UI -> counters = counters.copy(manualPauses = counters.manualPauses + 1)
        }
        generation += 1
        lastEvent = LastEvent(
            if (reason == PauseReason.COUGH) "cough-pause" else "pause",
            detail ?: "source=${source.name} line=${line?.id}",
            now(),
            confidence,
        )
        return NarrationAction.StopSpeech
    }

    private fun reasonFor(source: CommandSource): PauseReason = when (source) {
        CommandSource.VOICE -> PauseReason.VOICE
        CommandSource.MEDIA_BUTTON -> PauseReason.MEDIA_BUTTON
        CommandSource.COUGH_DETECTOR -> PauseReason.COUGH
        CommandSource.SYSTEM -> PauseReason.RESTORED
        CommandSource.UI -> PauseReason.UI
    }

    private fun speak(index: Int, restart: Boolean): NarrationAction {
        val line = script[index]
        generation += 1
        currentLineIndex = index
        return NarrationAction.SpeakLine(
            lineIndex = index,
            lineId = line.id,
            text = line.text,
            restart = restart,
            generation = generation,
        )
    }

    private fun commit(actions: MutableList<NarrationAction>): List<NarrationAction> {
        val snap = snapshot()
        actions += NarrationAction.Persist(snap)
        actions += NarrationAction.Publish(snap)
        return actions
    }
}

/** Millisecond monotonic clock, injectable for tests. */
object SystemClockLike {
    @JvmStatic
    var custom: (() -> Long)? = null

    @JvmStatic
    fun elapsed(): Long = custom?.invoke() ?: System.nanoTime() / 1_000_000L
}

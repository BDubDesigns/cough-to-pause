package dev.harness.coughtopause.adventure

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dev.harness.coughtopause.core.CommandSource
import dev.harness.coughtopause.core.PlaybackStatus
import dev.harness.coughtopause.platform.HarnessState
import dev.harness.coughtopause.platform.UiState
import dev.harness.coughtopause.rpg.BackgroundId
import dev.harness.coughtopause.rpg.Campaign
import dev.harness.coughtopause.rpg.CampaignId
import dev.harness.coughtopause.rpg.ChallengeKey
import dev.harness.coughtopause.rpg.CheckRequest
import dev.harness.coughtopause.rpg.CreateCampaignResult
import dev.harness.coughtopause.rpg.CheckOutcome
import dev.harness.coughtopause.rpg.DifficultyTier
import dev.harness.coughtopause.rpg.EventPayload
import dev.harness.coughtopause.rpg.GameCommand
import dev.harness.coughtopause.rpg.GameCommand.AttemptCheck
import dev.harness.coughtopause.rpg.EncounterStatus
import dev.harness.coughtopause.rpg.SpellCatalog
import dev.harness.coughtopause.rpg.SpellKind
import dev.harness.coughtopause.rpg.GameCommand.Travel
import dev.harness.coughtopause.rpg.ProposedFact
import dev.harness.coughtopause.rpg.WorldProposal
import dev.harness.coughtopause.rpg.RollImportance
import dev.harness.coughtopause.rpg.Submission
import dev.harness.coughtopause.rpg.Validated
import dev.harness.coughtopause.service.NarrationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.launch

data class AdventureUiState(
    val snapshot: AdventureSnapshot? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val voice: VoiceState = VoiceState.Idle,
    val narrationSource: String = DeterministicNarrator().status,
    val hasCampaign: Boolean = false,
    val aiConfigured: Boolean = false,
    val aiStatus: String = "",
    val aiBaseUrl: String = AiConfig.DEFAULT_BASE_URL,
    val aiModel: String = AiConfig.DEFAULT_MODEL,
    val aiKeyPresent: Boolean = false,
    val aiLastError: String? = null,
    val autoListen: Boolean = true,
)

/**
 * Android glue for the playable slice: character creation, command submission, narration and
 * one-shot voice input. All game state lives in the `:rpg` campaign; this class only translates
 * between the UI, the engine and the narration service.
 */
class AdventureController(private val context: Context) {

    private val engine = AdventureEngine(File(context.filesDir, "campaigns"))
    private val interpreter = IntentInterpreter()
    private val aiStore = AiConfigStore(context)
    private var aiConfig: AiConfig = aiStore.load()
    private var narrator: NarrativeProvider = buildNarrator(aiConfig)
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "adventure") }
    private val main = Handler(Looper.getMainLooper())

    /**
     * App-scoped on purpose. The hands-free loop must keep running while the Activity is stopped
     * or the screen is off, so it may not live in a Compose effect: Compose only observes [state].
     */
    private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handsFree = HandsFreeCoordinator()

    /** Contextual listening after Andy speaks; persisted so it survives a restart. */
    @Volatile
    private var autoListen: Boolean = context
        .getSharedPreferences("cough_to_pause", Context.MODE_PRIVATE)
        .getBoolean("auto_listen", true)

    private val _state = MutableStateFlow(
        AdventureUiState(
            narrationSource = narrator.status,
            aiConfigured = aiConfig.configured,
            aiStatus = narrator.status,
            aiBaseUrl = aiConfig.baseUrl,
            aiModel = aiConfig.model,
            aiKeyPresent = aiConfig.apiKey.isNotBlank(),
            autoListen = autoListen,
        ),
    )
    val state: StateFlow<AdventureUiState> = _state.asStateFlow()

    private var campaign: Campaign? = null
    private val transcript = mutableListOf<String>()

    private var recognizer: SpeechRecognizer? = null
    private var voice: VoiceCapture? = null

    /** True when the current capture came from the automatic window rather than a tap on Speak. */
    @Volatile
    private var autoCapture = false
    private var lastScene: List<String> = emptyList()

    /** Last mode seen from the service, so entering/leaving the adventure is detected once. */
    private var wasInAdventure = false

    /** Identifies the current window request, so a superseded handoff can never start a recogniser. */
    private var windowRequest = 0

    /**
     * A window has been requested from the service but the recogniser has not started yet.
     * Without this, a cough or a duplicate completion callback landing in the ~300 ms handoff
     * would look like "no window open" and ask for a second one.
     */
    @Volatile
    private var windowPending = false

    /** Restore runs once per process, not once per Activity re-creation. */
    private var restored = false

    init {
        // One collector, application scoped: a second one would open a second window.
        runtimeScope.launch { HarnessState.state.collect { shared -> handleSharedState(shared) } }
    }

    // ---------------------------------------------------------------- campaign lifecycle

    fun restore() {
        if (restored) return
        restored = true
        executor.execute {
            val ids = engine.listCampaigns()
            val loaded = ids.mapNotNull { engine.load(it) }.maxByOrNull { it.createdAtMs }
            if (loaded == null) {
                _state.update { it.copy(hasCampaign = false, message = "No campaign yet — create a character.") }
                return@execute
            }
            campaign = loaded
            transcript.clear()
            transcript += "Resumed ${loaded.player.name} in ${loaded.world.locations[loaded.currentLocationId]?.name ?: "the world"}."
            publish()
        }
    }

    /** Back to character creation without touching the saves. */
    fun startFresh() {
        campaign = null
        transcript.clear()
        _state.update {
            it.copy(snapshot = null, hasCampaign = false, busy = false, message = null, voice = VoiceState.Idle)
        }
    }

    /** Deletes every saved campaign, then returns to character creation. */
    fun deleteAllCampaigns() {
        executor.execute {
            val removed = engine.deleteAll()
            campaign = null
            transcript.clear()
            _state.update {
                it.copy(
                    snapshot = null,
                    hasCampaign = false,
                    busy = false,
                    message = "Deleted $removed saved campaign(s).",
                )
            }
        }
    }

    fun newAdventure(name: String, backgroundId: BackgroundId) {
        _state.update { it.copy(busy = true, message = null) }
        executor.execute {
            val seed = System.currentTimeMillis()
            val rolled = engine.rollAttributes(seed)
            val assignment = engine.autoAssign(rolled, backgroundId)
            when (val character = engine.createCharacter(name.ifBlank { "Wanderer" }, rolled, assignment, backgroundId)) {
                is Validated.Invalid -> {
                    _state.update { it.copy(busy = false, message = "Character rejected: ${character.errors.first()}") }
                }
                is Validated.Valid -> {
                    val id = CampaignId("c-$seed")
                    when (val created = engine.createCampaign(id, seed, character.value)) {
                        is CreateCampaignResult.Created -> {
                            campaign = created.campaign
                            transcript.clear()
                            transcript += "${character.value.name} the ${backgroundId.value.replace('_', ' ')} arrives."
                            publish()
                            narrate(emptyList(), preferLocationFirst = true, describePlace = true)
                        }
                        is CreateCampaignResult.Rejected ->
                            _state.update { it.copy(busy = false, message = "World rejected: ${created.errors.first()}") }
                        CreateCampaignResult.AlreadyExists ->
                            _state.update { it.copy(busy = false, message = "That campaign already exists.") }
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- play

    fun act(rawText: String) {
        val text = rawText.trim()
        if (text.isEmpty()) return
        val current = campaign ?: run {
            _state.update { it.copy(message = "Create a character first.") }
            return
        }
        _state.update { it.copy(busy = true, message = null) }
        executor.execute {
            // "Andy, go to the inn" and "go to the inn" are the same action. The name is an
            // address, not a wake word: nothing listens for it in the background.
            val action = WakePhrase.actionFrom(text)
            val world = WorldView(
                locationId = current.currentLocationId.value,
                locations = current.world.locations.values.map { it.id.value to it.name },
                npcs = current.world.npcs.values
                    .filter { it.locationId == current.currentLocationId }
                    .map { npc ->
                        NpcView(
                            id = npc.id.value,
                            name = npc.name,
                            topics = NpcTopics.forNpc(npc).map { it.key to it.label },
                        )
                    },
            )
            val intent = interpreter.interpret(action, world)
            transcript += "> $text"
            when (intent) {
                is AdventureIntent.Look -> {
                    transcript += "You look around."
                    publish()
                    narrate(emptyList(), preferLocationFirst = true, describePlace = true)
                }
                is AdventureIntent.Help -> {
                    transcript += "Try: look · go to <place> · talk to <name> · climb/search/persuade/read …"
                    publish()
                }
                // "Andy, repeat" and "Play" re-speak the saved prompt; nothing is submitted here.
                is AdventureIntent.Repeat -> replayOrResume()
                is AdventureIntent.Attack -> {
                    if (current.encounter?.status == EncounterStatus.ACTIVE) {
                        submit(current, GameCommand.Attack(engine.newActionId()))
                    } else {
                        val here = current.world.locations.getValue(current.currentLocationId)
                        if (here.creatures.isEmpty()) {
                            transcript += "Nothing here wants a fight."
                            publish()
                        } else {
                            // Opening move: start the fight, then the next "attack" swings.
                            submit(
                                current,
                                GameCommand.StartEncounter(engine.newActionId(), here.creatures.take(2)),
                            )
                        }
                    }
                }
                is AdventureIntent.Defend -> submit(current, GameCommand.Defend(engine.newActionId()))
                is AdventureIntent.Cast -> {
                    val spellId = intent.spell ?: SpellCatalog.EMBER_BOLT
                    val spell = SpellCatalog.byId(spellId)
                    if (spell == null) {
                        transcript += "You do not know that spell."
                        publish()
                    } else if (current.encounter?.status != EncounterStatus.ACTIVE && spell.kind == SpellKind.DAMAGE) {
                        transcript += "There is nothing to cast at."
                        publish()
                    } else {
                        submit(current, GameCommand.Cast(engine.newActionId(), spell.id, null))
                    }
                }
                is AdventureIntent.Use -> {
                    val item = intent.item ?: current.player.inventory.keys
                        .firstOrNull { engine.carriedHeals(it) }
                    if (item == null) {
                        transcript += "You are not carrying anything to use."
                        publish()
                    } else {
                        submit(current, GameCommand.UseItem(engine.newActionId(), item))
                    }
                }
                is AdventureIntent.Retreat -> submit(current, GameCommand.Retreat(engine.newActionId()))
                is AdventureIntent.Talk -> {
                    val npc = current.world.npcs[intent.npc]
                    if (npc == null) {
                        transcript += "There is no one called ${intent.name} here."
                        publish()
                    } else {
                        // Speak the person and what they will discuss, never the room again.
                        val lines = NpcTopics.lines(npc)
                        transcript += lines
                        publish()
                        speakLines(lines)
                    }
                }
                is AdventureIntent.Ask -> handleAsk(current, intent)
                is AdventureIntent.Travel -> submit(current, Travel(engine.newActionId(), intent.destination))
                is AdventureIntent.Check -> submit(
                    current,
                    AttemptCheck(
                        actionId = engine.newActionId(),
                        request = CheckRequest(
                            skill = intent.skill,
                            difficulty = intent.difficulty,
                            challenge = ChallengeKey(intent.subjectId, intent.topic),
                            intent = intent.label,
                            importance = RollImportance.IMPORTANT,
                        ),
                    ),
                )
                is AdventureIntent.Unknown -> {
                    transcript += "I did not understand that. Try \"look\", \"go to the inn\", or \"search the room\"."
                    publish()
                }
            }
        }
    }

    private fun submit(current: Campaign, command: GameCommand) {
        // Arriving somewhere is the one outcome that has to describe the new room.
        handle(engine.submit(current.id, command), current, describePlace = command is Travel)
    }

    private fun handle(result: Submission, current: Campaign, describePlace: Boolean = false) {
        when (result) {
            is Submission.Committed -> {
                campaign = engine.load(current.id) ?: current
                transcript += "The world answers."
                publish()
                narrate(result.events, preferLocationFirst = false, describePlace = describePlace)
            }
            is Submission.Replayed -> {
                transcript += "(already applied — replaying the same outcome)"
                publish()
                narrate(result.events, preferLocationFirst = false, describePlace = describePlace)
            }
            is Submission.Rejected -> {
                transcript += "Refused: ${result.errors.joinToString("; ") { it.toString() }}"
                _state.update { it.copy(busy = false) }
                publish()
            }
            is Submission.Unavailable -> {
                _state.update { it.copy(busy = false, message = result.reason) }
            }
        }
    }

    /**
     * "ask <npc> about <topic>". The check decides whether the fact is revealed; the fact itself is
     * authored data committed through the engine's normal world-proposal path, so a conversation
     * can never invent world truth.
     */
    private fun handleAsk(current: Campaign, intent: AdventureIntent.Ask) {
        val npc = current.world.npcs[intent.npc]
        if (npc == null) {
            transcript += "There is no one called ${intent.name} here."
            publish()
            return
        }
        val topics = NpcTopics.forNpc(npc)
        val topic = topics.firstOrNull { it.key == intent.topicKey }
        if (topic == null) {
            transcript += if (topics.isEmpty()) {
                "${npc.name} has nothing more to say."
            } else {
                "${npc.name} would talk about: " + topics.joinToString(", ") { it.label }
            }
            publish()
            return
        }
        if (current.world.facts.any { it.text == topic.fact }) {
            // The fallback region already seeds some of these facts. A player who asks anyway
            // hears them again — and is told why there is no die to roll.
            transcript += "${npc.name} confirms: ${topic.fact} (already known — no roll needed)"
            publish()
            narrate(
                emptyList(),
                preferLocationFirst = false,
                extraLines = listOf(topic.fact, "You already knew this, so there is nothing to roll."),
            )
            return
        }

        val check = AttemptCheck(
            actionId = engine.newActionId(),
            request = CheckRequest(
                skill = topic.skill,
                difficulty = DifficultyTier.MODERATE,
                challenge = ChallengeKey(npc.id.value, "topic-${topic.key}"),
                intent = "ask ${npc.name} about ${topic.label}",
                importance = RollImportance.IMPORTANT,
            ),
        )
        when (val result = engine.submit(current.id, check)) {
            is Submission.Committed -> {
                val succeeded = result.events.any { event ->
                    val outcome = (event.payload as? EventPayload.CheckResolved)?.roll?.outcome
                    outcome == CheckOutcome.SUCCESS || outcome == CheckOutcome.AUTOMATIC_SUCCESS
                }
                if (!succeeded) {
                    campaign = engine.load(current.id) ?: current
                    transcript += "${npc.name} deflects: \"Another time.\""
                    publish()
                    narrate(result.events, preferLocationFirst = false)
                    return
                }
                val commit = engine.submit(
                    current.id,
                    GameCommand.CommitWorldProposal(
                        engine.newActionId(),
                        WorldProposal(facts = listOf(ProposedFact(npc.id.value, topic.fact))),
                    ),
                )
                campaign = engine.load(current.id) ?: current
                transcript += "${npc.name} tells you: ${topic.fact}"
                val events = result.events + (commit as? Submission.Committed)?.events.orEmpty()
                publish()
                narrate(events, preferLocationFirst = false, extraLines = listOf(topic.fact))
            }
            else -> handle(result, current)
        }
    }

    private fun narrate(
        events: List<dev.harness.coughtopause.rpg.GameEvent>,
        preferLocationFirst: Boolean,
        extraLines: List<String> = emptyList(),
        /** Only when the room is news: arriving somewhere, or the player asked to look. */
        describePlace: Boolean = false,
    ) {
        val current = campaign ?: return
        var lines = NarrationScriptBuilder.build(current, events, narrator, preferLocationFirst, extraLines, describePlace)
        if (lines.isEmpty() && narrator.id == "llm") {
            // The model failed: say so once, then narrate deterministically rather than going silent.
            val reason = (narrator as? LlmNarrator)?.lastError ?: "unavailable"
            transcript += "(AI unavailable: $reason — scripted narration)"
            _state.update { it.copy(aiLastError = reason) }
            lines = NarrationScriptBuilder.build(
                current,
                events,
                DeterministicNarrator(),
                preferLocationFirst,
                extraLines,
                describePlace,
            )
        }
        if (lines.isEmpty()) {
            _state.update { it.copy(busy = false) }
            return
        }
        lastScene = lines.map { it.text }
        lines.forEach { transcript += "${WakePhrase.NAME}: ${it.text}" }
        NarrationService.narrateScene(
            context,
            NarrationScriptBuilder.sceneId(current, events),
            lines.map { it.text },
        )
        _state.update { it.copy(busy = false) }
    }

    private fun publish() {
        val current = campaign
        _state.update {
            it.copy(
                snapshot = current?.let { c -> engine.snapshot(c, transcript, narrator.status) },
                hasCampaign = current != null,
                busy = false,
                narrationSource = narrator.status,
                aiConfigured = aiConfig.configured,
                aiStatus = narrator.status,
                aiBaseUrl = aiConfig.baseUrl,
                aiModel = aiConfig.model,
                aiKeyPresent = aiConfig.apiKey.isNotBlank(),
            )
        }
    }

    // ---------------------------------------------------------------- AI settings

    private fun buildNarrator(config: AiConfig): NarrativeProvider =
        if (config.configured) LlmNarrator(config) else DeterministicNarrator()

    fun saveAiConfig(baseUrl: String, model: String, apiKey: String) {
        val config = AiConfig(
            baseUrl = baseUrl.trim().ifBlank { AiConfig.DEFAULT_BASE_URL },
            model = model.trim().ifBlank { AiConfig.DEFAULT_MODEL },
            apiKey = apiKey.trim(),
        )
        aiStore.save(config)
        aiConfig = config
        narrator = buildNarrator(config)
        _state.update {
            it.copy(
                aiConfigured = config.configured,
                aiStatus = narrator.status,
                aiBaseUrl = config.baseUrl,
                aiModel = config.model,
                aiKeyPresent = config.apiKey.isNotBlank(),
                aiLastError = null,
                narrationSource = narrator.status,
                message = if (config.configured) "AI settings saved." else "AI key cleared — scripted narration.",
            )
        }
    }

    /** One tiny request so the player can confirm the key before playing. */
    fun testAi() {
        val llm = narrator as? LlmNarrator
        if (llm == null) {
            _state.update { it.copy(aiLastError = "no key configured") }
            return
        }
        _state.update { it.copy(busy = true) }
        executor.execute {
            val reply = llm.probe()
            _state.update {
                it.copy(
                    busy = false,
                    aiLastError = llm.lastError,
                    message = if (reply != null) "AI replied: $reply" else "AI test failed: ${llm.lastError}",
                )
            }
        }
    }

    // ---------------------------------------------------------------- contextual window

    /**
     * Called on every shared-state change, from process start until the app is killed — not from a
     * Compose effect. The state it reads is published by the foreground service, which is the
     * microphone owner and keeps running when this Activity is stopped.
     *
     * The coordinator decides; this class performs the side effects and the service does the work.
     */
    private fun handleSharedState(shared: UiState) {
        val narration = shared.narration
        // The service owns the mode: leaving the adventure closes the window and disarms the wake
        // gesture even if the Activity is already gone.
        if (shared.adventureMode != wasInAdventure) {
            wasInAdventure = shared.adventureMode
            if (!shared.adventureMode) {
                windowRequest += 1
                handsFree.reset()
                stopVoice()
            }
        }
        val request = handsFree.onState(
            status = narration.status,
            scriptId = narration.scriptId,
            generation = narration.generation,
            detectionAtMs = shared.lastDetection?.atMs,
            inAdventure = shared.adventureMode,
            autoListenEnabled = autoListen,
            windowOpen = windowPending || voice?.isListening == true,
            nowMs = SystemClock.elapsedRealtime(),
        ) ?: return
        when (request.reason) {
            HandsFreeCoordinator.Reason.NARRATION_COMPLETED ->
                _state.update { it.copy(message = "Listening — say \"Andy, …\" to answer, or ignore it.") }
            HandsFreeCoordinator.Reason.COUGH ->
                _state.update { it.copy(message = "Cough heard - listening. Say \"Andy, ...\".") }
        }
        startVoice(fromAutoWindow = true)
    }

    /** "Play"/"Resume" while paused restarts the interrupted line; after that it repeats the scene. */
    private fun replayOrResume() {
        val status = HarnessState.state.value.narration.status
        when (ReplayPolicy.decide(status, hasLastScene = lastScene.isNotEmpty())) {
            ReplayAction.RESUME -> {
                transcript += "(resuming the interrupted line)"
                publish()
                NarrationService.resume(context)
            }
            ReplayAction.REPLAY -> replayLastScene()
            ReplayAction.IGNORE -> {
                transcript += "Andy has not said anything to repeat yet."
                publish()
            }
        }
    }

    // Replay the last prompt from saved lines; never re-runs rules, dice or rewards.
    /**
     * Speak lines that are not a scene description (talking to someone): the room is not re-read,
     * and the scene id still starts with `rpg-`, so the contextual window opens when Andy finishes.
     */
    private fun speakLines(lines: List<String>) {
        if (lines.isEmpty()) return
        lastScene = lines
        NarrationService.narrateScene(context, "rpg-talk-" + System.currentTimeMillis(), lines)
    }

    fun replayLastScene() {
        if (lastScene.isEmpty()) {
            // Nothing has been narrated in this session yet (fresh launch after restore): speak the
            // current scene instead. Still narration only — no command is submitted.
            narrate(emptyList(), preferLocationFirst = true, describePlace = true)
            return
        }
        // A fresh scene id: the service reloads the script (new narration generation), so the
        // automatic response window opens again when the repeat finishes.
        NarrationService.narrateScene(context, "rpg-replay-" + System.currentTimeMillis(), lastScene)
        transcript += "(replayed the last prompt)"
        _state.update { it.copy(message = "Repeating the last prompt - answer when Andy finishes.") }
        publish()
    }

    fun setAutoListen(enabled: Boolean) {
        autoListen = enabled
        handsFree.resetAutoListen()
        context.getSharedPreferences("cough_to_pause", Context.MODE_PRIVATE)
            .edit().putBoolean("auto_listen", enabled).apply()
        _state.update { it.copy(autoListen = enabled) }
    }

    // ---------------------------------------------------------------- voice input

    fun startVoice(fromAutoWindow: Boolean = false) {
        if (windowPending || voice?.isListening == true) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _state.update { it.copy(voice = VoiceState.Failed("no speech recogniser on this device")) }
            return
        }
        autoCapture = fromAutoWindow
        val request = ++windowRequest
        windowPending = true
        // The detector owns the microphone while narrating. Ask the service to let go, then wait
        // until it actually has: a recogniser built too early just records silence.
        val serialBefore = HarnessState.state.value.micWindowSerial
        NarrationService.openVoiceWindow(context, VOICE_WINDOW_MS)
        awaitMicrophone(request, fromAutoWindow, serialBefore)
    }

    /** Poll until the detector has released the microphone, then start the recogniser. */
    private fun awaitMicrophone(request: Int, fromAutoWindow: Boolean, serialBefore: Long) {
        val startedAt = SystemClock.elapsedRealtime()
        val poll = object : Runnable {
            override fun run() {
                // A newer request (or stopVoice) supersedes this one: never start a stale window.
                if (request != windowRequest) return
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                val state = HarnessState.state.value
                val handoff = MicHandoff.evaluate(
                    owner = state.micOwner,
                    // Served *and* released: the serial rules out a stale owner from a previous
                    // window, which would otherwise look like an instant release.
                    serialServed = state.micWindowSerial > serialBefore,
                    elapsedMs = elapsed,
                )
                when (handoff) {
                    MicHandoff.WAITING -> main.postDelayed(this, MicHandoff.POLL_MS)
                    MicHandoff.RELEASED -> {
                        HarnessState.log("mic: detector released after ${elapsed}ms - recogniser starting")
                        beginCapture(fromAutoWindow)
                    }
                    MicHandoff.TIMED_OUT -> {
                        // Nothing released it in time — most likely no detector is running at all.
                        // Say so and try anyway; a recogniser error still closes the window safely.
                        HarnessState.log(
                            "mic: no detector release after ${MicHandoff.DEFAULT_TIMEOUT_MS}ms - starting anyway",
                        )
                        beginCapture(fromAutoWindow)
                    }
                }
            }
        }
        main.post(poll)
    }

    private fun beginCapture(fromAutoWindow: Boolean) {
        if (voice?.isListening == true) return
        windowPending = false
        autoCapture = fromAutoWindow
        val capture = VoiceCapture(clock = { SystemClock.elapsedRealtime() })
        voice = capture
        _state.update { it.copy(voice = capture.start()) }

        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    val next = capture.onResult(text)
                    _state.update { it.copy(voice = next) }
                    if (next is VoiceState.Captured) {
                        if (autoCapture && !WakePhrase.acceptsAutoCapture(next.text)) {
                            // The window also hears the room; do not answer speech not aimed at Andy.
                            _state.update {
                                it.copy(message = "Heard \"${next.text.take(40)}\" — not addressed to Andy, ignored.")
                            }
                        } else {
                            act(next.text)
                        }
                    }
                    stopVoice()
                    NarrationService.closeVoiceWindow(context)
                }

                override fun onError(error: Int) {
                    val next = capture.onError(error)
                    _state.update { it.copy(voice = next) }
                    stopVoice()
                    NarrationService.closeVoiceWindow(context)
                }

                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                },
            )
        }

        // Timeout watchdog: a recogniser that never answers must not leave the UI stuck.
        val ticker = object : Runnable {
            override fun run() {
                val next = capture.tick()
                if (next != _state.value.voice) _state.update { it.copy(voice = next) }
                if (capture.isListening) {
                    main.postDelayed(this, 500)
                } else {
                    stopVoice()
                    NarrationService.closeVoiceWindow(context)
                }
            }
        }
        main.postDelayed(ticker, 500)
    }

    fun stopVoice() {
        // Cancel any pending handoff so a poll cannot start a recogniser after this call.
        windowRequest += 1
        windowPending = false
        recognizer?.let { runCatching { it.stopListening() }; runCatching { it.destroy() } }
        recognizer = null
        voice?.reset()
        voice = null
        NarrationService.closeVoiceWindow(context)
    }

    /** Quit: stop the window, forget the scene, and let the next launch restore from disk. */
    fun quit() {
        stopVoice()
        handsFree.reset()
        campaign = null
        transcript.clear()
        restored = false
        _state.update {
            it.copy(snapshot = null, hasCampaign = false, busy = false, message = null, voice = VoiceState.Idle)
        }
    }

    companion object {
        @Volatile
        private var instance: AdventureController? = null

        /**
         * The one controller for this process, built on the application context.
         *
         * Application scoped so the hands-free loop survives the Activity being stopped, paused or
         * destroyed, and so the Activity context is never retained by it.
         */
        fun obtain(context: Context): AdventureController =
            instance ?: synchronized(this) {
                instance ?: AdventureController(context.applicationContext).also { instance = it }
            }

        /** Bounded: a window that never closes would leave the cough detector off. */
        private const val VOICE_WINDOW_MS = 8_000
    }
}

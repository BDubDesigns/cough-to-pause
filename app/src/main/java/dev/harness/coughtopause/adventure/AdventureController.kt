package dev.harness.coughtopause.adventure

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dev.harness.coughtopause.core.CommandSource
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
import dev.harness.coughtopause.rpg.GameCommand.Travel
import dev.harness.coughtopause.rpg.ProposedFact
import dev.harness.coughtopause.rpg.WorldProposal
import dev.harness.coughtopause.rpg.RollImportance
import dev.harness.coughtopause.rpg.Submission
import dev.harness.coughtopause.rpg.Validated
import dev.harness.coughtopause.service.NarrationService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.Executors

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

    private val _state = MutableStateFlow(
        AdventureUiState(
            narrationSource = narrator.status,
            aiConfigured = aiConfig.configured,
            aiStatus = narrator.status,
            aiBaseUrl = aiConfig.baseUrl,
            aiModel = aiConfig.model,
            aiKeyPresent = aiConfig.apiKey.isNotBlank(),
        ),
    )
    val state: StateFlow<AdventureUiState> = _state.asStateFlow()

    private var campaign: Campaign? = null
    private val transcript = mutableListOf<String>()

    private var recognizer: SpeechRecognizer? = null
    private var voice: VoiceCapture? = null

    // ---------------------------------------------------------------- campaign lifecycle

    fun restore() {
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
                            narrate(emptyList(), preferLocationFirst = true)
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
                    narrate(emptyList(), preferLocationFirst = true)
                }
                is AdventureIntent.Help -> {
                    transcript += "Try: look · go to <place> · talk to <name> · climb/search/persuade/read …"
                    publish()
                }
                is AdventureIntent.Talk -> {
                    val npc = current.world.npcs[intent.npc]
                    transcript += if (npc != null) {
                        "${npc.name} (${npc.role}): ${npc.description}"
                    } else {
                        "There is no one called ${intent.name} here."
                    }
                    publish()
                    narrate(emptyList(), preferLocationFirst = false)
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
        handle(engine.submit(current.id, command), current)
    }

    private fun handle(result: Submission, current: Campaign) {
        when (result) {
            is Submission.Committed -> {
                campaign = engine.load(current.id) ?: current
                transcript += "The world answers."
                publish()
                narrate(result.events, preferLocationFirst = false)
            }
            is Submission.Replayed -> {
                transcript += "(already applied — replaying the same outcome)"
                publish()
                narrate(result.events, preferLocationFirst = false)
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
            transcript += "${npc.name} has already told you that."
            publish()
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
                narrate(events, preferLocationFirst = false)
            }
            else -> handle(result, current)
        }
    }

    private fun narrate(events: List<dev.harness.coughtopause.rpg.GameEvent>, preferLocationFirst: Boolean) {
        val current = campaign ?: return
        var lines = NarrationScriptBuilder.build(current, events, narrator, preferLocationFirst)
        if (lines.isEmpty() && narrator.id == "llm") {
            // The model failed: say so once, then narrate deterministically rather than going silent.
            val reason = (narrator as? LlmNarrator)?.lastError ?: "unavailable"
            transcript += "(AI unavailable: $reason — scripted narration)"
            _state.update { it.copy(aiLastError = reason) }
            lines = NarrationScriptBuilder.build(current, events, DeterministicNarrator(), preferLocationFirst)
        }
        if (lines.isEmpty()) {
            _state.update { it.copy(busy = false) }
            return
        }
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

    // ---------------------------------------------------------------- voice input

    fun startVoice() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _state.update { it.copy(voice = VoiceState.Failed("no speech recogniser on this device")) }
            return
        }
        // The detector owns the microphone while narrating; hand it over before listening.
        NarrationService.openVoiceWindow(context, VOICE_WINDOW_MS)
        val capture = VoiceCapture(clock = { android.os.SystemClock.elapsedRealtime() })
        voice = capture
        _state.update { it.copy(voice = capture.start()) }

        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    val next = capture.onResult(text)
                    _state.update { it.copy(voice = next) }
                    if (next is VoiceState.Captured) act(next.text)
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
        recognizer?.let { runCatching { it.stopListening() }; runCatching { it.destroy() } }
        recognizer = null
        voice?.reset()
        voice = null
        NarrationService.closeVoiceWindow(context)
    }

    fun shutdown() {
        stopVoice()
        executor.shutdownNow()
    }

    private companion object {
        /** Bounded: a window that never closes would leave the cough detector off. */
        const val VOICE_WINDOW_MS = 8_000
    }
}

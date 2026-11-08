# Adventure slice (M1, first playable)

The smallest end-to-end experience on top of the `:rpg` foundation: create a character, arrive in
the fallback world, act, roll, and hear it all through the same narration engine the cough-to-pause
harness uses.

Refs [#1](https://github.com/BDubDesigns/cough-to-pause/issues/1).

## What is playable

1. **Character creation.** Attributes are rolled by the engine (4d6-drop-lowest with its safeguard)
   and assigned by background priority, then `CharacterFactory` validates the result.
2. **Starting region.** `FallbackRegion.proposal` is validated and committed by `Rules.newCampaign`.
   No AI-generated world in this slice.
3. **Location narration.** The current location, its NPCs and its exits are spoken through the
   existing `TtsNarrator` + `NarrationController`, so a cough pauses and "Play" restarts **that
   line** exactly as in M0.
4. **Natural input with a fallback.** Type an action, or tap **Speak** for a one-shot on-device
   recogniser (7 s timeout, then "type instead"). Free-form text is interpreted by
   `IntentInterpreter`, not by a model.
5. **Exploration, NPCs, one check.** `go to <place>` → `Travel`; `talk to <name>` → NPC description;
   any skill keyword (`search`, `climb`, `persuade`, `read`, …) → `AttemptCheck` with a MODERATE
   tier, the NPC or location as subject, and `RollImportance.IMPORTANT`.
6. **Dice and progression on screen.** The last roll shows d20, total, DC and outcome; skill XP is
   listed per skill.
7. **Persistence.** Every command goes through `ActionProcessor`, which saves atomically. Relaunching
   restores the newest campaign; the narration checkpoint survives process death because the scene
   lines are persisted beside it.
8. **M0 preserved.** `Start` on the harness screen always reloads the harness script, so the audio
   experiment is unchanged.

## Talking to Andy

The dungeon master is called **Andy** in the UI, in the journal, and in the narration provenance
line.

The name is an optional **address prefix**, not a wake word:

- Typed: `Andy, go to the inn` and `go to the inn` are the same action.
- Spoken: tap **Speak**, then say `Andy, look around` or just `look around`.
- `Andy?` or `hey Andy` on its own asks Andy to describe where you are.

`WakePhrase.strip` removes one leading `andy` / `hey andy` / `okay andy` (with punctuation,
case-insensitive) and otherwise leaves the text untouched, so `candy shop` is not treated as an
address. Nothing listens for the name in the background in this slice.

## Hands-free design (next slice — not implemented here)

The intended loop, recorded so the next slice does not invent a different one:

1. Andy finishes a narration prompt; the app opens a **short contextual response window**.
2. During the window, speech recognition owns the microphone and the detector is off.
3. Silence or window timeout does nothing: no action, no error, and the detector comes back.
4. A recognised utterance becomes the player's action, exactly as if typed.
5. Outside contextual windows, **"Hey Andy"** will activate the window (wake word).
6. Meanwhile cough detection is unchanged: a cough pauses narration, "Play" resumes that line.

This PR implements the **handoff primitive** that loop needs — `MicOwnership` plus the service's
bounded voice window — but the window is opened by tapping Speak, not automatically.

### Microphone ownership

| State | Owner | Why |
| --- | --- | --- |
| Andy narrating | cough detector | continuous detection; a cough must never be missed |
| Paused | harness recogniser | coughs are ignored while paused; "Play"/"Resume" must work |
| Adventure voice window open | adventure recogniser | the player explicitly asked to speak |
| Window closed | back to the playback policy | the detector returns immediately |

Rules are implemented in `MicOwnership` (pure, unit tested) and enforced by
`NarrationService.applyOwner`. The window is bounded (8 s) and closes on result, error, timeout or
an explicit stop, so the detector can never be left switched off.

## Wake word: evaluate before training

A wake word is **not** in this PR. Before proposing a custom model, evaluate off-the-shelf on-device
engines:

| Option | Custom word for "Andy" | Data needed | Notes |
| --- | --- | --- | --- |
| **Porcupine** (Picovoice), low-level API | Yes — keyword trained in the Console or via the training API, downloaded as a `.ppn` | **None** | Accepts raw PCM frames, so it can consume the detector's existing 16 kHz stream without a second capture. Needs an AccessKey and a licence check for shipping. |
| openWakeWord | Limited set of pretrained words per model | Depends on the word | ONNX rather than TFLite; would need an ONNX runtime added. |
| sherpa-onnx KWS | Yes, with pretrained keyword-spotting models | None for common words | Open source, on-device, larger runtime. |
| Custom TFLite model | Yes | Recordings plus off-device training | Last resort, not first. |

Earlier notes in this repo said a custom wake word needs "hundreds of recordings" — that is wrong
for Porcupine, which trains custom keywords **without** user data. Try the Porcupine low-level API
on the detector's stream first, measure false accepts while Andy narrates, and suppress detection
while TTS is speaking (Andy's own narration says its name).

## What is not AI

| Part | Today | To become a real dungeon master |
| --- | --- | --- |
| Narration | `DeterministicNarrator` — prose derived from committed facts and resolved events | `LlmNarrator` (the seam exists, returns `null` when unconfigured) |
| Intent parsing | `IntentInterpreter` — keyword → command | A prompt that emits `CheckRequest`/`Travel` candidates for the engine to validate |
| World generation | `FallbackRegion` only | `WorldProposalParser` + `CommitWorldProposal` behind the same validator |

The UI prints the narrator's provenance ("Scripted narration (no AI dungeon master configured)")
so a scripted line is never presented as generated prose.

### Configuration required for a real LLM

1. `android.permission.INTERNET` in the manifest — deliberately **not** added yet, so the app cannot
   silently talk to a network endpoint.
2. Provider endpoint, model id and API key, stored in app settings (never in the repo); the app has
   no such screen yet.
3. `LlmNarrator.describeLocation`/`describeOutcome` implemented against that endpoint, with a
   timeout and a deterministic fallback on any failure.
4. A prompt contract that returns a **bounded** structure: narration lines, plus at most one
   proposed `Intent`/`CheckRequest`. The engine already refuses out-of-bounds modifiers, unknown
   subjects and duplicate action ids; the model must never be trusted for difficulty *semantics* —
   that stays the orchestrator's call, per the PR #2 review.

## What is deliberately missing

Combat, encounters, gold spending, items, crafting, housing, level-up UI, multiple regions, and any
AI call. `StartEncounter` and `SpendGold` exist in the engine but are not wired to input.

## Try it on the Pixel

1. Open the app → **Adventure →**.
2. Name your character, pick a background, tap **Begin**. You should hear the starting location.
3. Try: `look`, `go to the inn`, `talk to <name>`, `search the room`, `persuade <name>`.
4. Cough mid-line: narration pauses; say "Play" and that line restarts.
5. Force-stop the app, reopen it: the same campaign and the same checkpoint come back.

## Test coverage added by this slice

`./scripts/build.sh :app:testDebugUnitTest` — 72 tests, including:

- `AdventureEngineTest` — character → fallback world → check resolved → **replay** of the same action
  id returns the original events and changes nothing → reload from a fresh engine sees the same state.
- `IntentInterpreterTest` — travel/talk/check/look/unknown mapping, including the "help" false
  positive that used to swallow "persuade Mira to help".
- `VoiceCaptureTest` — result, silence **timeout**, late result ignored, empty result, recogniser error.
- `NarrationScriptBuilderTest` — scene lines are numbered, capped, and start with the place.
- `NarrationControllerTest` — loading a scene script keeps the cough-checkpoint-resume contract.

Unit tests do not prove microphone or Bluetooth behaviour; that remains the device playtest in
[`playtest.md`](playtest.md).

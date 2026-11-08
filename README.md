# Cough-to-Pause — M0 audio harness

A native Android harness that proves one thing: **narration stops the instant you cough, and resumes
the exact interrupted line when you say "Play"** — with the audio path entirely on the device.

This is an audio engineering experiment, not a game. No network, no accounts, no LLM, no RPG.

| Proven on hardware | Not yet proven |
| --- | --- |
| TTS narration through the device speaker and earbuds | Detection accuracy across 20+ attempts per audio route |
| Deliberate cough → immediate pause, ~35 ms detect-to-silence | Bluetooth earbud playback / earbud-microphone routing |
| Interrupted-line checkpoint survives pause and process death | 20+ minute pause recovery |
| Spoken "Play"/"Resume" resumes the same line, offline recogniser | Extended conversation false-positive rate |
| Restricted vocabulary ("play", "resume", "pause", "stop", "continue") | Battery drain over a long session |
| Media-button pause/resume fallback | Reliability of YAMNet vs a cough-specific model on real coughs |
| 52 JVM unit tests, green | Locked-screen, airplane-mode, BT reconnect behaviour |

Known-good test device: **Pixel 10 Pro XL, Android 17 (API 37)**, build `CP3A.261005.005` — see
[`docs/measurements.md`](docs/measurements.md).

## What it does

1. Narrates a fixed 12-line script with `TextToSpeech`.
2. Holds the microphone in a foreground service and analyses 0.5 s windows every 250 ms.
3. On a cough: stops speech in the same main-looper turn, saves `(line id, index, reason)`.
4. Stays paused through conversation, further coughs and silence — coughs while paused are counted,
   not acted on.
5. Recognises "Play"/"Resume" (offline recogniser, restricted vocabulary) and restarts **that whole
   line**, never replaying completed lines.
6. Accepts spoken "Pause", plus a media-button fallback.
7. Persists the checkpoint; a session that was playing when the process died comes back **paused**,
   never auto-playing.

## Detection

Two backends behind one gate, selectable at runtime as an experiment:

- **DSP** — broadband-transient scorer: adaptive noise floor, onset sharpness, high-band ratio,
  spectral flatness, zero-crossing rate, burst duration. No model, ~0.2 ms per window.
- **YAMNet** — AudioSet classifier through TensorFlow Lite, `Cough` (42) / `Throat clearing` (43),
  with a speech-suppression rule. Measured **5 ms per inference** on the Pixel 10 Pro XL.
- **Hybrid** (default) — DSP is the fast gate, YAMNet confirms. This is what the harness ships with.

A cooldown gate (default 1200 ms) turns several overlapping windows of one cough into one pause.
Calibration captures real cough windows and background windows on the device and picks the
threshold that keeps the false-positive rate at or below 10 %.

No microphone audio is written to disk or sent anywhere; one ring buffer is recycled in memory.

## Adventure (M1, first playable slice)

`Adventure →` in the app opens a small playable loop on top of the deterministic `:rpg` engine:
create a character, arrive in the validated fallback world, `look` / `go to …` / `talk to …` /
`search` / `persuade …`, see the roll and the skill XP, and hear it narrated through the same
cough-to-pause pipeline. Text input and one-shot voice input both work. Narration is
**scripted**, not AI — see [`docs/adventure.md`](docs/adventure.md) for what is simulated and what
configuration a real dungeon master would need.

## Build (on the phone, no PC)

Requires the toolchain from `android_sdk_install`: OpenJDK 21, `aapt2` (aarch64), Gradle.

```sh
./scripts/build.sh :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./scripts/build.sh :app:testDebugUnitTest    # 52 JVM tests
./scripts/build.sh :rpg:test                 # RPG rules module, pure JVM
```

Three Android-specific overrides are baked into `scripts/build.sh`, each one discovered the hard way:

1. `-Dorg.gradle.native=false` — Gradle's `native-platform` `.so` is glibc; Android is bionic.
2. `-Djava.io.tmpdir=$PREFIX/tmp` — the packaged JDK hardcodes a Termux `/tmp` that does not exist.
3. `env -u LD_PRELOAD` — the termux-exec preload library breaks the `aapt2` daemon.
   `-Dandroid.aapt2FromMavenOverride=$(command -v aapt2)` points AGP at the aarch64 `aapt2`.

## Install and run

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk   # or: pm install -r -t <apk>
```

Grant microphone (and notification) permission, then `Start`. Diagnostics live in the second screen:
last event and confidence, latency stats, audio route and AEC state, precision/recall, battery,
capture health (analysed windows, silent reads, peak level, screen state) and a **Near misses** table
that records every window which came within 55 % of the threshold — so a cough that failed to pause
leaves evidence of *why* it failed.

## Layout

```
app/src/main/java/dev/harness/coughtopause/
  core/        pure Kotlin, no Android imports — everything here is unit tested
    Narration.kt        state machine + checkpoint + actions
    Detection.kt        settings, noise floor, cooldown/confirmation gate
    Dsp.kt              FFT, features, cough scorer
    Calibration.kt      threshold selection, operator-labelled metrics
    Persistence.kt      JSON codecs (restart-survival is tested through these)
    VoiceCommands.kt    restricted vocabulary parsing
  platform/    TTS, AudioRecord engine, YAMNet, recogniser, route/battery probes, UI state
  service/     foreground service (mic type), media session, notification, persistence
  ui/          Compose screens
app/src/test/java/...  52 tests: state machine, races, persistence, gate, DSP, calibration
rpg/           M1 RPG rules (pure Kotlin/JVM, not yet wired into the app) — see docs/rpg-domain.md
docs/          playtest protocol, measurements, detector recommendation, RPG domain contracts
```

## Honest limitations

- **Single-capture arbitration.** Android gives capture to one client at a time: with the app's own
  `AudioRecord` running, the platform recogniser never receives audio. While **paused** the
  recogniser owns the mic, so "Play"/"Resume" is immediate. While **playing** the detector owns it
  continuously, which is the default because every recognition slice is a window in which a cough
  cannot be heard. Ticking *Voice while narrating* restores a 1.5 s slice every 6 s, at the cost of
  that blind spot.
- **Another device's speaker is not cancellable.** Playing narration through a car stereo or a
  speaker the phone does not own means the phone mic hears full-level speech; the local echo
  canceller has no reference for it and YAMNet classifies it as speech, so a distant cough has to
  win against it. Earbuds (narration inside the ears, mic hearing only the room) are the clean case.
- **Speaker self-trigger.** Narrating through the phone speaker produced a false positive
  (score 0.59, YAMNet-confirmed). AEC was active; this is the strongest argument for earbuds.
- **DSP is a heuristic.** Fricatives and throat-clearing can score like a cough. Calibration helps;
  a cough-specific model is the likely end state.
- **YAMNet is coarse.** "Cough" comes from AudioSet, trained on YouTube audio, not on this phone's
  microphone placement. Confidence is a class score, not a calibrated probability.
- **Recogniser cycles.** The on-device recogniser ends a silent window after ~5 s and is restarted
  immediately; very short words spoken during the gap are missed.
- **Not yet measured:** battery over hours, locked screen, BT disconnect/reconnect, 20+ minute pauses.

## Out of scope

The M0 harness itself contains no RPG systems. M1 is under way in [issue #1](https://github.com/BDubDesigns/cough-to-pause/issues/1):
the deterministic rules foundation lives in the separate `:rpg` module ([`docs/rpg-domain.md`](docs/rpg-domain.md))
and is not yet connected to the audio app. Still out of scope for the harness: LLM integration, world generation, inventory/combat/progression, wake phrases beyond the
required pause/resume controls, multiplayer, monetisation, backend services.

# Recorded measurements

Every number below came from a real tool result or a real device log. Anything not yet measured is
listed as *pending* rather than estimated.

## Environment

| Item | Value |
| --- | --- |
| Device | Pixel 10 Pro XL (`mustang`), Google |
| Android | 17, API 37, build `CP3A.261005.005`, `user/release-keys` |
| Page size | 4096 B (no 16 KB-page native-lib constraint) |
| App | `dev.harness.coughtopause` 0.1.0, debug, minSdk 26, targetSdk/compileSdk 37 |
| APK | 31.1 MB signed debug APK, incl. 4.1 MB YAMNet model + TFLite arm64 JNI |
| Build | on-device Gradle 9.9 + AGP 9.5.1 + Kotlin 2.4.21; incremental `assembleDebug` ≈ 40–55 s |

## Model

| Item | Value |
| --- | --- |
| Model | YAMNet float32 TFLite (MediaPipe assets), 4,126,810 B, md5 `d02e1b838813107817b755d09d6b56b3` |
| Tensors | input `[15600]`, output `[1, 521]` |
| Class indices used | Cough 42, Throat clearing 43, Speech 0, Laughter 13, Sneeze 44 |
| Inference time | **5 ms** per 0.975 s window (logged every 2 s by the diagnostics screen) |
| Runtime | TensorFlow Lite 2.17.0, 2 threads, XNNPACK delegate (seen in logcat) |

YAMNet costs ~2 % of one core at a 250 ms hop. It is fast enough; the open question is accuracy, not
speed.

## Detection

| Measurement | Value | Conditions |
| --- | --- | --- |
| Detect → audio stop latency | **35 ms** | HYBRID, phone speaker narration, AEC active, n=1 |
| False positive under speaker narration | **1** (score 0.59, YAMNet-confirmed) | narration audible through the phone speaker into the mic |
| DSP cost per window | *pending instrumentation* | — |
| Calibration path | exercised in unit tests; capture flow installed | on-device calibration round pending |

The single 35 ms figure is honest but thin: it is one event, measured as
`elapsedRealtime()` at detection minus `elapsedRealtime()` after `TextToSpeech.stop()` returned.
The playtest protocol collects 20+ per configuration.

## Voice commands

| Item | Value |
| --- | --- |
| On-device recogniser available | `true` on this device (API 37, English offline pack present) |
| Recognition windows | ~5 s per session; `ERROR_NO_MATCH` restart in 120 ms on the same session |
| Command latency | *pending measurement* (bounded by the recognition window and, while playing, the 1.5 s slice) |
| Failure found in playtest #1 | recogniser never became ready — it was constructed on the mic worker thread (no `Looper`). Fixed; after the fix the log shows `voice: ready for speech` and "Play" resumes the interrupted line. |

## Automated tests

`./scripts/build.sh :app:testDebugUnitTest` — **52 tests, 0 failures, 0 errors**:

| Test class | Tests | Covers |
| --- | --- | --- |
| `NarrationControllerTest` | 18 | start/cough/pause/resume ordering, checkpoint identity, 20-cycle pause/resume, ignored coughs while paused, stale TTS callbacks, cough-vs-completion race, script completion, restore semantics |
| `DspScorerTest` | 7 | synthetic cough vs voiced speech vs silence vs steady noise, gain variation, short-window safety |
| `DetectionGateTest` | 10 | threshold, cooldown, confirmation windows, sensitivity mapping, calibration gain, noise-floor tracking |
| `PersistenceTest` | 5 | JSON round-trip, corrupt input, full save → "process death" → restore → resume |
| `CalibrationTest` | 4 | separable and overlapping classes, false-positive budget, degenerate input |
| `MetricsTest` | 5 | latency percentiles, precision/recall, rejection counters, reset |
| `VoiceCommandsTest` | 3 | restricted vocabulary, last-word-wins, non-command rejection |

These prove the state machine and the maths. They cannot prove microphone behaviour — that is what the
tables above are for.

## Manual playtest log

| # | Config | Round | Result |
| --- | --- | --- | --- |
| 1 | Phone speaker, phone mic, screen on | informal, operator | Cough pauses narration immediately (operator: "coughing pauses it instantly"). Voice "play"/"resume" failed → root-caused to the recogniser thread bug, fixed and re-verified on device. No instrumented counts taken. |
| 2 | Phone speaker, phone mic, screen on | instrumented 20-cough round | *pending* |
| 3 | Bluetooth earbuds (A2DP) + phone mic | 20 coughs | *pending* |
| 4 | Bluetooth earbuds, earbud mic (SCO/BLE) | 20 coughs + recalibration | *pending* |
| 5 | Conversation / laughter / throat clearing | 10 min + 5 + 5 | *pending* |
| 6 | Locked screen | 2 min then cough | *pending* |
| 7 | 20+ minute pause, force-stop during pause | recovery | *pending* |
| 8 | Bluetooth disconnect / reconnect | mid-narration | *pending* |
| 9 | Airplane mode, cold start | full loop | *pending* |

## Battery

*Pending.* The Diagnostics screen already records level, instantaneous current, average drain and a
charge-counter-based %/hour once ≥ 2 minutes of samples exist. Fill this table from a 30-minute run:

| Config | Duration | Drain mA (avg) | %/hour | Notes |
| --- | --- | --- | --- | --- |
| detector only (paused) | | | | |
| narrating + detector | | | | |
| narrating + detector, earbuds | | | | |

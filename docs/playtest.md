# Hardware playtest protocol

The harness is only proven when real-device numbers are recorded. This is the script for that.

Record every run. A run without numbers is an anecdote.

## Before each configuration

1. `Settings → Apps → Cough-to-Pause → Battery → Unrestricted` (otherwise Doze will kill the mic).
2. Open the app, confirm Diagnostics shows `service: running`, a microphone route, and
   `YAMNet ok · in [15600] out [1,521] · inference N ms`.
3. Tap **Reset counters** in Diagnostics.
4. Note the configuration in the results table: route, earbuds model, detector backend,
   sensitivity, cooldown.

## R1 — Baseline, phone speaker + phone mic, screen on

- Start narration. Cough **20 times**, spread across different lines and different words.
- After each cough, wait for the pause, then say "Play". Confirm the **same line** restarts.
- Log: pauses triggered, misses (tap *Missed a cough*), false positives (tap *Was not a cough*).
- Fill the latency row from Diagnostics (`avg / p95 / max`).

Pass: ≥ 18/20 detected, ≤ 1 false positive, resume works every time.

## R2 — Bluetooth earbuds (A2DP playback, phone microphone)

- Connect earbuds, start narration, confirm Diagnostics `output` names the earbuds.
- Repeat the 20-cough round. Rate TTS clarity 1–5 (rate any dropouts, muffle, or latency).
- Note whether the mic route stays the phone mic or changes to the earbuds.

## R3 — Bluetooth earbuds, earbud microphone (where supported)

- Same 20 coughs, but confirm Diagnostics `input` names the earbud mic (SCO/BLE).
- Expect a different score distribution: closer mouth, different colouration, likely louder.
  Re-run **calibration** for this route and apply it, then repeat.
- Rate TTS quality again — SCO playback is narrow-band and usually sounds worse.

## R4 — Conversation false positives (no coughing)

- Start narration, then hold a normal 10-minute conversation in the room, and read a paragraph aloud.
- Count pauses that were not coughs. This is the number that decides whether the harness is usable.
- Repeat with laughter and throat-clearing deliberately (5 each) and note which ones triggered.

## R5 — Locked screen

- Start narration, lock the screen, leave it 2 minutes, then cough.
- Verify: pause still happens, and "Play" still resumes with the screen locked.
- Note whether Android shows the foreground-service notification and whether mic capture survives.

## R6 — Long pause (20+ minutes)

- Cough → paused → leave the phone 20 minutes (screen may lock; use airplane mode if you like).
- Then say "Play". Verify the checkpoint is the same line and that resume is immediate.
- Also force-stop the app during the pause, reopen it, and confirm it restores **paused** at that line.

## R7 — Bluetooth disconnect/reconnect

- During narration, switch the earbuds off. Note what happens (pause? route change? silence?).
- Reconnect, verify narration/TTS still works and the detector keeps running.

## R8 — Airplane mode

- Enable airplane mode, force-stop, relaunch.
- Verify narration, detection and *voice* commands all still work (they must: nothing uses network).
- If the recogniser reports `network` errors here, record it — it means the offline language pack
  is missing and the "local" claim is conditional on the device.

## Recording

Per run, copy these from Diagnostics into `docs/measurements.md`:

- cough pauses / false positives / missed coughs → precision & recall
- latency avg, p95, max
- route (`input`/`output`), AEC requested/active, record rate
- detector backend, sensitivity, thresholds, cooldown
- battery: level start/end, `avg drain` and `rate` from the Diagnostics battery card
- free-text: anything audible (clicks, dropouts, TTS cut-offs)

## Also worth capturing

- A failed cough: which backend scored what (`dsp=… yamnet=…` appears in the event log).
- The exact word you said when a voice command failed ("play" vs "resume" vs "play the story").
- Whether the pause happened on the cough's onset or ~0.5 s late (that is a windowing effect, not a
  pipeline stall).

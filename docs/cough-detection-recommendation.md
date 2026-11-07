# Cough detection: what to ship, and what to test next

## Recommendation for M0

**Ship the hybrid: DSP transient gate in front of YAMNet, with on-device calibration.**

Reasons, all from what was measured on the Pixel 10 Pro XL:

- YAMNet is *responsive enough*. 5 ms per window means the detector can run at a 250 ms hop with two
  threads and still have ~98 % of the budget free. Latency is dominated by the analysis window, not
  the model.
- YAMNet alone is too coarse. Its "Cough" class comes from AudioSet, which is YouTube audio captured
  with unknown microphones. It also fires on throat clearing and, at least once here, on TTS speech
  played through the speaker. A class score is not a calibrated probability.
- DSP alone is too eager. Fricatives and throat clearing can look like a broadband transient. The
  threshold that stops missing quiet coughs also starts catching laughter.
- Together they are better than either: the DSP gate costs nothing and rejects most sustained
  material before the model runs, and YAMNet rejects the noise bursts that survive the gate. This is
  the default backend for that reason.
- Calibration is not optional. The same cough scores very differently through a phone mic, an earbud
  mic and an SCO link. The harness therefore computes thresholds from windows captured on the device
  for the route in use, and stores them per installation.

So: **keep YAMNet as the reference classifier, use DSP as the gate, and treat the threshold as
per-route state rather than a constant.**

## What would change the answer

The decision should flip to a small cough-specific model if the playtest shows any of:

- Recall below ~85 % at a false-positive rate the operator tolerates (roughly one pause per 10
  minutes of conversation), on any single route.
- Laughter or throat clearing triggering pauses more than occasionally — YAMNet cannot separate them
  from coughing, and no threshold fixes a class that is genuinely the same class.
- YAMNet's confidence drifting with microphone gain, so that one calibration does not survive a
  room change.

## The concrete next step (cheapest credible model)

Do not train a cough model from scratch on day one. Use the harness to collect a dataset on the
phone, then fit a head on top of what YAMNet already computes:

1. **Collect labelled windows in-app.** The calibration flow already captures positives (deliberate
   coughs) and negatives (conversation). Extend it to dump the **1024-d YAMNet embedding** for each
   window to app-private storage — embeddings only, never raw audio, which keeps the "no recordings
   by default" promise.
2. **Fit a logistic regression / 2-layer MLP** on those embeddings for the specific routes in use
   (phone speaker, earbuds A2DP, earbuds SCO). A few hundred positives and a few thousand negatives
   are enough for a linear head; it runs in microseconds on-device.
3. **Evaluate against the same playtest protocol** and compare precision/recall and latency against
   the hybrid. Keep whichever wins per route, guarded by the same cooldown and confirmation gate.
4. Only if the head is not enough: a genuinely small CNN/YAMNet-lite on mel patches, or a TFLite
   model fine-tuned on the collected data. That is a bigger project and should not start before the
   embedding head has been measured.

## Engineering constraints any replacement must keep

- **No raw audio retention by default.** Windows live in one ring buffer; anything persisted must be
  features or embeddings, and must be opt-in.
- **Bounded latency.** Detection must stay inside the analysis hop; the model cannot be allowed to
  make the stop latency worse than the ~35 ms already measured.
- **Deterministic gates.** Cooldown and confirmation live in pure Kotlin and are unit tested; a model
  swap must not move that logic into the model.
- **Same measurement surface.** Any new backend must report score, backend, peak dB, SNR and a stop
  latency into the diagnostics screen, otherwise it cannot be compared with the ones already measured.

## Known failure modes worth designing around

- **Speaker self-trigger.** TTS through the phone speaker is picked up by the mic; AEC helped but did
  not eliminate it (one YAMNet-confirmed false positive at score 0.59). Earbuds, or ducking narration
  gain during the analysis window, are the mitigations to test.
- **Single-capture arbitration.** While the app holds `AudioRecord`, the platform recogniser gets no
  audio. The harness therefore alternates microphone ownership: recogniser while paused, detector
  while playing with a 1.5 s recognition slice every 6 s. Any future detector must be cheap enough to
  tolerate being stopped and restarted that often.
- **Windowing is not alignment.** A cough that starts just after a window boundary is detected on the
  next window; the 35 ms stop latency is the pipeline, and the extra ~250 ms is the measurement grid.

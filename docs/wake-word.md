# Wake word ("Hey Andy"): the experiment

Part of #8. The contextual window already makes Andy answerable hands-free once he has spoken;
this is about waking him from silence without a tap.

## Why not Porcupine

Picovoice removed the free tier. Checked while planning; it is out.

## Why not sherpa-onnx (measured)

Downloaded `sherpa-onnx-2.0.1.aar` and measured it rather than guessing:

| Component | Size |
| --- | --- |
| AAR total | 48 MB |
| `libonnxruntime.so` (arm64) | 22.2 MB |
| `libsherpa-onnx-jni.so` (arm64) | 4.6 MB |
| Native payload, one ABI | **~27 MB** |
| KWS model on top | ~10–30 MB |

It is also not on Maven Central (GitHub releases or JitPack only). For a single two-word keyword
that is a bad trade in an app that already ships TensorFlow Lite.

## Chosen approach: custom openWakeWord, exported to TFLite

- The app **already ships a TFLite interpreter** (YAMNet) and **already holds 16 kHz float frames**
  from the cough detector, so a custom model costs **~200 KB and no new native runtime**.
- Training is a notebook with a synthesised dataset (TTS positives plus negatives) — no recordings
  of the user required.
- Licence: train our own model. The pretrained openWakeWord models are CC-BY-NC-SA, which we must
  not ship.

## The experiment, in order

1. **Train** a `hey andy` model from the openWakeWord notebook; export ONNX, convert to TFLite.
2. **Load it beside YAMNet** in the existing interpreter path and feed it the detector's ring
   buffer (no second capture, no `AudioRecord` handover).
3. **Gate** it: kill detection while TTS speaks and for ~1 s after, because Andy's narration says
   his own name.
4. **Measure** on the Pixel 10 Pro XL:
   - false accepts per hour with normal conversation and with narration playing (the hard case);
   - detection rate for a quiet "hey andy" vs a loud one, arms-length and in a pocket;
   - CPU/battery cost per hour awake with the detector already running.
5. **Decide** the default: on, off, or on-only-when-configured.

## What the spike must not do

- Stop the cough detector during narration (no periodic mic slices).
- Auto-listen forever: the wake word opens the same bounded window as today.
- Act on speech that never addressed Andy — the tap-permissive / auto-strict split stays.

## Failure criteria

Ship the wake word only if, at a false-accept rate the playtest tolerates (roughly one spurious
open per hour of conversation), it detects a normal-volume "hey andy" most of the time from the
same distances the game is played at. Otherwise the contextual window remains the way to talk.

#!/usr/bin/env python
"""Prove the three TFLite files satisfy the Android inference contract, by running them exactly
the way the app must: 1280-sample hops, TFLite only, no openwakeword code in the loop.

    python verify_android_contract.py --mel melspectrogram.tflite --emb embedding_model.tflite \
        --clf hey_andy.tflite [--clf-onnx hey_andy.onnx] [--oww]

Checks (exit 1 on any failure):
  1. tensor shapes/dtypes of all three models match the contract below;
  2. streaming scores from the TFLite chain equal an ONNX reference chain (needs the sibling
     .onnx files next to the .tflite ones, and --clf-onnx);
  3. with --oww (openwakeword installed): the TFLite embeddings equal openwakeword's own
     `AudioFeatures` streaming output, i.e. the Android loop below reproduces upstream.

Contract (what AudioRecord code in the app must do):
  PCM       16 kHz mono, 16-bit samples fed to the models as float32 in int16 range (+-32768),
            NOT normalised to +-1.
  Hop       1280 samples (80 ms).  mel input = last 1280+480 samples  -> [1, 1760].
  mel       melspectrogram.tflite: input [1,N] (must be resized: resizeInput + allocateTensors),
            output [1,1,8,32]; apply x/10 + 2 to every value; append 8 frames to a mel ring.
  embed     embedding_model.tflite: input [1,76,32,1] = last 76 mel frames, output [1,1,1,96].
            One embedding per hop once 76 mel frames exist.
  classify  hey_andy.tflite: input [1,16,96] = last 16 embeddings (oldest first), output [1,1]
            sigmoid score in 0..1.  Threshold ~0.5 (tune on device).
"""
import argparse
import os
import sys

import numpy as np

HOP, CTX = 1280, 480
FAILED = []


def check(ok, msg):
    print(("PASS  " if ok else "FAIL  ") + msg)
    if not ok:
        FAILED.append(msg)


def tflite(path):
    from ai_edge_litert.interpreter import Interpreter
    it = Interpreter(model_path=path)
    it.allocate_tensors()
    return it


def run(it, x):
    i, o = it.get_input_details()[0], it.get_output_details()[0]
    if list(i["shape"]) != list(x.shape):
        it.resize_tensor_input(i["index"], list(x.shape))
        it.allocate_tensors()
        i, o = it.get_input_details()[0], it.get_output_details()[0]
    it.set_tensor(i["index"], x.astype(np.float32))
    it.invoke()
    return it.get_tensor(o["index"]).copy()


def ort(path):
    import onnxruntime as ort_
    s = ort_.InferenceSession(path, providers=["CPUExecutionProvider"])
    n = s.get_inputs()[0].name
    return lambda x: s.run(None, {n: x.astype(np.float32)})[0]


def stream(audio, mel_fn, emb_fn, clf_fn):
    """The Android loop. audio: float32 in int16 range. Returns (embeddings[T,96], scores[T])."""
    mel_ring, embs, scores = [], [], []
    for start in range(0, len(audio) - HOP + 1, HOP):
        lo = max(0, start - CTX)
        chunk = audio[lo:start + HOP]
        if len(chunk) < HOP + CTX:                      # first hop: left-pad context with zeros
            chunk = np.concatenate([np.zeros(HOP + CTX - len(chunk), np.float32), chunk])
        m = np.squeeze(mel_fn(chunk[None, :])) / 10.0 + 2.0      # [8,32]
        mel_ring.extend(list(m))
        mel_ring = mel_ring[-76 * 4:]
        if len(mel_ring) >= 76:
            win = np.array(mel_ring[-76:], np.float32)[None, :, :, None]
            embs.append(np.asarray(emb_fn(win)).reshape(96))
        if len(embs) >= 16:
            scores.append(float(np.asarray(clf_fn(np.array(embs[-16:], np.float32)[None])).reshape(-1)[0]))
    return np.array(embs), np.array(scores)


def make_audio(seconds=8, seed=0):
    rng = np.random.default_rng(seed)
    t = np.arange(int(16000 * seconds)) / 16000.0
    f0 = 120 + 40 * np.sin(2 * np.pi * 0.7 * t)
    voiced = sum(np.sin(2 * np.pi * np.cumsum(f0) / 16000 * h) / h for h in range(1, 12))
    env = np.clip(np.sin(2 * np.pi * 1.3 * t), 0, None) ** 2
    x = 6000 * env * voiced + 800 * rng.normal(size=t.shape)
    return np.clip(x, -32768, 32767).astype(np.int16).astype(np.float32)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mel", required=True)
    ap.add_argument("--emb", required=True)
    ap.add_argument("--clf", required=True)
    ap.add_argument("--clf-onnx")
    ap.add_argument("--oww", action="store_true")
    a = ap.parse_args()

    mel, emb, clf = tflite(a.mel), tflite(a.emb), tflite(a.clf)
    ci, co = clf.get_input_details()[0], clf.get_output_details()[0]
    ei, eo = emb.get_input_details()[0], emb.get_output_details()[0]
    mi, mo = mel.get_input_details()[0], mel.get_output_details()[0]
    check(list(ci["shape"]) == [1, 16, 96] and ci["dtype"] == np.float32, f"classifier input {ci['shape'].tolist()} float32 == [1,16,96]")
    check(list(co["shape"]) == [1, 1] and co["dtype"] == np.float32, f"classifier output {co['shape'].tolist()} float32 == [1,1]")
    check(list(ei["shape"]) == [1, 76, 32, 1] and list(eo["shape"]) == [1, 1, 1, 96], f"embedding {ei['shape'].tolist()} -> {eo['shape'].tolist()}")
    check(list(mi["shape"]) == [1, 1] and mi["dtype"] == np.float32, f"melspectrogram input {mi['shape'].tolist()} (resizable)")
    check(run(mel, np.zeros((1, HOP + CTX), np.float32)).shape == (1, 1, 8, 32), "mel of 1760 samples -> [1,1,8,32] (8 frames per 80 ms hop)")
    for name, it in (("classifier", clf), ("embedding", emb), ("melspectrogram", mel)):
        ops = sorted({d["op_name"] for d in it._get_ops_details()} - {"DELEGATE"})  # DELEGATE = XNNPACK, a runtime artefact
        bad = [o for o in ops if o.upper().startswith(("CUSTOM", "FLEX"))]
        check(not bad, f"{name} ops are all TFLite builtins: {', '.join(ops)}")

    audio = make_audio()
    e_t, s_t = stream(audio, lambda x: run(mel, x), lambda x: run(emb, x), lambda x: run(clf, x))
    check(len(s_t) > 40 and np.isfinite(s_t).all() and (0 <= s_t).all() and (s_t <= 1).all(),
          f"{len(s_t)} streamed scores, all finite and in [0,1] (min {s_t.min():.3f} max {s_t.max():.3f})")

    base = os.path.dirname(os.path.abspath(a.mel))
    mo_, eo_ = os.path.join(base, "melspectrogram.onnx"), os.path.join(base, "embedding_model.onnx")
    if a.clf_onnx and os.path.exists(mo_) and os.path.exists(eo_):
        e_o, s_o = stream(audio, ort(mo_), ort(eo_), ort(a.clf_onnx))
        check(np.abs(e_t - e_o).max() < 1e-3, f"embeddings tflite vs onnx max diff {np.abs(e_t - e_o).max():.2e}")
        check(np.abs(s_t - s_o).max() < 1e-3, f"scores tflite vs onnx max diff {np.abs(s_t - s_o).max():.2e}")
    else:
        print("SKIP  onnx reference (need --clf-onnx and sibling melspectrogram.onnx / embedding_model.onnx)")

    if a.oww:
        from openwakeword.utils import AudioFeatures
        F = AudioFeatures(melspec_model_path=a.mel, embedding_model_path=a.emb, inference_framework="tflite")
        F.reset()
        ref = []
        pcm = audio.astype(np.int16)
        for s in range(0, len(pcm) - HOP + 1, HOP):
            F(pcm[s:s + HOP])
            ref.append(F.get_features(1)[0, -1])
        ref = np.array(ref)
        n = min(len(ref), len(e_t))
        # upstream pre-fills its buffers with noise, so only compare once both rings are full
        d = np.abs(ref[-(n - 76 // 8 - 2):] - e_t[-(n - 76 // 8 - 2):]).max()
        check(d < 2e-2, f"embeddings vs openwakeword AudioFeatures (tail, ring full) max diff {d:.2e}")

    print("\nRESULT:", "FAIL" if FAILED else "OK")
    return 1 if FAILED else 0


if __name__ == "__main__":
    sys.exit(main())

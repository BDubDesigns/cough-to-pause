# Hey Andy wake word: training and export toolchain

Part of #8. Nothing here touches the Android app. It produces three TFLite files the app would load
beside YAMNet: `hey_andy.tflite` (classifier) plus openWakeWord's `melspectrogram.tflite` and
`embedding_model.tflite`.

## What was wrong with the old workflow

| Symptom | Cause (reproduced in a clean Linux venv) |
| --- | --- |
| `openwakeword[train]` | No such extra. PyPI has `test` and `full`; `full` pins `tensorflow-cpu==2.8.1`, `onnx==1.14.0`, `protobuf<4`, `onnx_tf==1.10.0`. |
| `python -m openwakeword.train --help` | The training CLI is a **script** (`openwakeword/train.py`) with `--training_config`, `--generate_clips`, `--augment_clips`, `--train_model`. It needs a YAML, piper-sample-generator and datasets. `--help` imports torch and the whole training stack first. |
| Endless TF/onnx2tf/protobuf/keras pin churn | One environment was trying to be both the PyTorch trainer and the TensorFlow converter. Their pins are mutually exclusive. The classifier is a tiny torch MLP, so the stacks never need to share a process. **Fix: two environments joined by a `.onnx` file.** |
| `import acoustics` fails | `acoustics==0.2.6` imports `scipy.special.sph_harm`, removed in scipy 1.17. Pin `scipy==1.16.3`. |
| `torch.onnx.export` needs `onnxscript` | torch >= 2.9 defaults to the dynamo exporter. Pin `torch==2.5.1` (legacy exporter, what upstream was written against). |
| `import pronouncing` fails | Needs `pkg_resources`; pin `setuptools<81`. |
| piper-sample-generator `generate_samples.py` not found | `main` was restructured into a package. `train.py` imports the old layout; use tag **v2.0.0** (matches the `.pt` voice). |
| Converted TFLite takes input `[1, 96, 16]` | onnx2tf treats a rank-3 input as NCW and transposes it. torch names the input `onnx::Flatten_0`; onnx2tf sanitises the `::`, so its `keep_*` layout flags silently miss it. `convert_to_tflite.py` renames the input first and then fails the build unless TFLite matches onnxruntime. |
| Upstream `--convert_to_tflite` | Uses `onnx_tf` (unmaintained, TF 2.8). Not used; onnx2tf replaces it. |

## Environments (Python 3.11)

| | train (`requirements-train.lock.txt`) | convert (`requirements-convert.lock.txt`) |
| --- | --- | --- |
| Frameworks | torch 2.5.1, torchaudio 2.5.1 | tensorflow 2.19.0, tf-keras 2.19.0, keras 3.15.1 |
| ONNX | onnx 1.17.0, onnxruntime 1.20.1 | onnx 1.19.1, onnxruntime 1.23.0, onnx2tf 1.29.24, onnxsim 0.4.36 |
| numpy / protobuf | 1.26.4 / 7.36.2 | 1.26.4 / **4.25.5** |
| Other | openWakeWord @ `368c037`, scipy 1.16.3, speechbrain 0.5.14, torchmetrics 1.2.0, audiomentations 0.33.0, piper-phonemize-cross 1.2.1 | ai-edge-litert 2.1.0 |

`requirements-*.txt` are the intent; `*.lock.txt` are the exact freezes the checks below ran on.
piper-sample-generator `v2.0.0` (`195e3bd`) is cloned by the `full` mode, not pip-installed.

Pretrained preprocessing models (openWakeWord release v0.5.1, sha256 checked by `run_pipeline.sh`):

| File | Bytes | sha256 |
| --- | --- | --- |
| `melspectrogram.tflite` | 1,092,516 | `96fa0adc...e68bf163e8` |
| `embedding_model.tflite` | 1,330,312 | `c0aea21e...3a59d8` |

## Commands

```sh
pip install uv                                   # fast installs, fetches Python 3.11 itself
sh scripts/wakeword/run_pipeline.sh smoke  WORK  # CPU, minutes; exit status is the verdict
sh scripts/wakeword/run_pipeline.sh full   WORK  # GPU + data (below)
```

Full run: open `hey_andy_colab.ipynb` on a GPU runtime (it calls `setup`, `prepare_data.py`, `full`).
GitHub-hosted runners have neither a GPU nor room for the ~17 GB negative-feature file, so CI only runs
`smoke` (`.github/workflows/train-wake-word.yml`).

## Android inference contract (checked by `verify_android_contract.py`)

PCM is 16 kHz mono, fed as float32 **in int16 range** (not normalised to +-1).

1. Every 1280 samples (80 ms) call `melspectrogram.tflite` on the last **1760** samples (1280 + 480
   context). Input is dynamic: `resizeInput(0, [1, 1760])` then `allocateTensors()`. Output
   `[1,1,8,32]`; apply `x/10 + 2` to every value; append the 8 frames to a ring.
2. Once 76 frames exist, `embedding_model.tflite` takes the last 76 frames as `[1,76,32,1]` and yields
   `[1,1,1,96]`: one embedding per hop.
3. `hey_andy.tflite` takes the last 16 embeddings, oldest first, `[1,16,96]` float32 and yields
   `[1,1]` in 0..1. Start at threshold 0.5 and tune on device.

All three use only TFLite builtin ops at opcode version 1 (classifier: FULLY_CONNECTED, MEAN, SUB, MUL,
ADD, RSQRT, RESHAPE, LOGISTIC), so the app's `org.tensorflow:tensorflow-lite:2.17.0` needs no Flex
delegate or extra dependency. Classifier size is about 205 KB at `layer_size: 32`.

## What was verified, and how

Verified on Linux x86_64, Python 3.11.18, 4 CPUs, no GPU:

* Both environments install from the lockfiles into **fresh** venvs and `run_pipeline.sh smoke` exits 0
  (train env, convert env, assets, smoke train, conversion, contract checks).
* `smoke_classifier.py` runs openWakeWord's real `Model.auto_train` (3 sequences, checkpoint merge) and
  `export_model` on synthetic features: ONNX opset 13, input `[1,16,96]`.
* `convert_to_tflite.py`: TFLite vs onnxruntime max abs difference 1.2e-07 (200 random inputs).
* `verify_android_contract.py`: shapes/dtypes/ops above; TFLite chain vs ONNX chain scores differ by
  2.2e-07; the TFLite-only streaming loop reproduces openWakeWord's own `AudioFeatures` embeddings
  with **0.0** difference.

## Not verified / limitations

* **No trained Hey Andy model exists yet.** The smoke classifier is trained on random clusters; it proves
  plumbing, not detection. The artifact `smoke_classifier.tflite` must not be shipped.
* **TTS clip generation, augmentation and real training were not run.** That sandbox had no GPU,
  blocked `huggingface.co` (all datasets and the negative features live there), and the harness declined
  to execute the cloned piper-sample-generator code. `prepare_data.py`, `hey_andy_colab.ipynb` and the
  `full` branch of `run_pipeline.sh` are transcribed from the official notebook and `train.py`, with
  the dependency problems above fixed, but have **not** been executed end to end.
* Not run on an Android runtime or device. Python's `ai-edge-litert` 2.1.0 executed the models; op
  versions suggest TFLite 2.17 will accept them, but the dynamic `resizeInput` on the mel model and
  real-time cost still need the Pixel playtest in `docs/playtest.md`.
* `train.py` hard-codes `val_set_hrs = 11.3` (the stock validation set). A different false-positive
  validation set makes its FP/hour numbers wrong.
* Pretrained openWakeWord models are CC-BY-NC-SA. `melspectrogram`/`embedding_model` are Google
  speech-embedding derivatives (Apache-2.0 per upstream README), but confirm their licence before
  bundling them in a shipped build.

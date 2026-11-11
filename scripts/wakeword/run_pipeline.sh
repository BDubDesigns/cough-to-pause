#!/bin/sh
# Reproducible Hey Andy pipeline.  Usage:
#   sh run_pipeline.sh setup [WORKDIR]    just the pinned environments + checksummed assets
#   sh run_pipeline.sh smoke [WORKDIR]    CPU, ~5 min after installs, no network beyond GitHub+PyPI
#   sh run_pipeline.sh full  [WORKDIR]    needs a GPU and the data from prepare_data.py
# Install uv first (pip install uv): it is ~10x faster than pip through the torch/CUDA wheels, and it
# fetches Python 3.11 itself.  pip works but is very slow here.
# Exit status is the verdict.  Python 3.11 is required (both lockfiles were resolved on it).
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
MODE=${1:-smoke}
WORK=${2:-$PWD/wakeword-work}
PY=${PYTHON:-python3.11}
OWW_SHA=368c03716d1e92591906a84949bc477f3a834455
PIPER_TAG=v2.0.0
REL=https://github.com/dscripka/openWakeWord/releases/download/v0.5.1
mkdir -p "$WORK/assets" "$WORK/out"

say() { printf '\n== %s\n' "$*"; }

venv() {  # venv NAME REQFILE  — create once, install from the exact lockfile
  if [ ! -x "$WORK/$1/bin/python" ]; then
    if command -v uv >/dev/null 2>&1; then      # uv fetches its own 3.11 (Colab ships 3.12)
      uv venv -q -p 3.11 "$WORK/$1"
      uv pip install -q -p "$WORK/$1/bin/python" -r "$2"
    else
      "$PY" -m venv "$WORK/$1"
      "$WORK/$1/bin/python" -m pip install -q --upgrade pip
      "$WORK/$1/bin/python" -m pip install -q -r "$2"
    fi
  fi
}

fetch_assets() {  # pinned, checksummed preprocessing models used by the Android app
  while read -r sum name; do
    [ -f "$WORK/assets/$name" ] && [ "$(sha256sum "$WORK/assets/$name" | cut -d' ' -f1)" = "$sum" ] && continue
    curl -fsSL -o "$WORK/assets/$name" "$REL/$name"
    echo "$sum  $WORK/assets/$name" | sha256sum -c - >/dev/null
  done <<'SUMS'
ba2b0e0f8b7b875369a2c89cb13360ff53bac436f2895cced9f479fa65eb176f melspectrogram.onnx
96fa0adccb6e8cf95cb14465409a1a2898ee4a96a85bb9ed3c7eb0e68bf163e8 melspectrogram.tflite
70d164290c1d095d1d4ee149bc5e00543250a7316b59f31d056cff7bd3075c1f embedding_model.onnx
c0aea21eb84a4ce90a08c870da41b7a7173b45269e6a3207c71d67c40f3a59d8 embedding_model.tflite
SUMS
}

convert_and_verify() {  # convert_and_verify ONNX TFLITE
  TF_CPP_MIN_LOG_LEVEL=3 "$WORK/convert-env/bin/python" -I "$HERE/convert_to_tflite.py" "$1" "$2"
  # verify in the train env too so --oww can cross-check against upstream's AudioFeatures
  "$WORK/train-env/bin/python" -I "$HERE/verify_android_contract.py" \
    --mel "$WORK/assets/melspectrogram.tflite" --emb "$WORK/assets/embedding_model.tflite" \
    --clf "$2" --clf-onnx "$1" --oww
}

say "environments"
venv train-env "$HERE/requirements-train.lock.txt"
venv convert-env "$HERE/requirements-convert.lock.txt"
say "assets"
fetch_assets

case "$MODE" in
  smoke)
    say "smoke: train synthetic classifier (real auto_train + ONNX export)"
    "$WORK/train-env/bin/python" -I "$HERE/smoke_classifier.py" "$WORK/out/smoke" --steps 300
    say "smoke: ONNX -> TFLite -> Android contract"
    convert_and_verify "$WORK/out/smoke/smoke_classifier.onnx" "$WORK/out/smoke/smoke_classifier.tflite"
    ;;
  full)
    cd "$WORK"
    [ -d piper-sample-generator ] || git clone -q --branch "$PIPER_TAG" --depth 1 https://github.com/rhasspy/piper-sample-generator.git
    [ -f piper-sample-generator/models/en_US-libritts_r-medium.pt ] || \
      curl -fsSL -o piper-sample-generator/models/en_US-libritts_r-medium.pt \
        "https://github.com/rhasspy/piper-sample-generator/releases/download/$PIPER_TAG/en_US-libritts_r-medium.pt"
    # openWakeWord's AudioFeatures looks for its mel/embedding models inside the package
    RES=$("$WORK/train-env/bin/python" -I -c 'import openwakeword,os;print(os.path.join(os.path.dirname(openwakeword.__file__),"resources","models"))')
    mkdir -p "$RES"; cp "$WORK"/assets/*.onnx "$WORK"/assets/*.tflite "$RES"/
    cp "$HERE/hey_andy.yml" "$WORK/hey_andy.yml"
    T="$WORK/train-env/bin/python -I $WORK/train-env/lib/python3.11/site-packages/openwakeword/train.py --training_config hey_andy.yml"
    say "full: generate clips";  $T --generate_clips
    say "full: augment";         $T --augment_clips
    say "full: train + ONNX";    $T --train_model
    say "full: ONNX -> TFLite -> Android contract"
    convert_and_verify "$WORK/out/hey_andy.onnx" "$WORK/out/hey_andy.tflite"
    ;;
  setup) ;;   # environments + assets only (done above); used by the Colab notebook
  *) echo "usage: sh run_pipeline.sh setup|smoke|full [WORKDIR]" >&2; exit 2 ;;
esac
say "OK ($MODE)"

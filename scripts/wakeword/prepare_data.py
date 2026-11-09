#!/usr/bin/env python
"""Download the training data the official openWakeWord notebook uses, into ./data.

    python prepare_data.py [--audioset-tar bal_train09.tar] [--fma-hours 1]

NOT VERIFIED in the session that wrote it: that sandbox blocks huggingface.co.  It is a
transcription of notebooks/automatic_model_training.ipynb (openWakeWord @ 368c037); if a
dataset id has moved, fix it here.  Needs ~20 GB of disk (the features file alone is ~17 GB).
Run it with the train env's python (it needs `datasets`, `scipy`).
"""
import argparse
import os
import subprocess
import tarfile
from pathlib import Path

import numpy as np
import scipy.io.wavfile

HF = "https://huggingface.co/datasets"


def wget(url, out):
    if not os.path.exists(out):
        subprocess.run(["curl", "-fL", "--retry", "3", "-o", out, url], check=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--audioset-tar", default="bal_train09.tar")
    ap.add_argument("--fma-hours", type=float, default=1)
    a = ap.parse_args()
    import datasets
    from tqdm import tqdm

    os.makedirs("data", exist_ok=True)

    # 1. Room impulse responses (MIT environmental IRs)
    out = "data/mit_rirs"
    if not os.path.isdir(out):
        os.makedirs(out)
        ds = datasets.load_dataset("davidscripka/MIT_environmental_impulse_responses", split="train", streaming=True)
        for row in tqdm(ds, desc="RIRs"):
            name = row["audio"]["path"].split("/")[-1]
            scipy.io.wavfile.write(os.path.join(out, name), 16000, (row["audio"]["array"] * 32767).astype(np.int16))

    # 2. Background noise: one AudioSet balanced-train shard, resampled to 16 kHz wav
    out = "data/audioset_16k"
    if not os.path.isdir(out):
        os.makedirs(out)
        tar = f"data/{a.audioset_tar}"
        wget(f"{HF}/agkphysics/AudioSet/resolve/main/data/{a.audioset_tar}", tar)
        with tarfile.open(tar) as t:
            t.extractall("data/audioset")
        files = [str(p) for p in Path("data/audioset").glob("**/*.flac")]
        ds = datasets.Dataset.from_dict({"audio": files}).cast_column("audio", datasets.Audio(sampling_rate=16000))
        for row in tqdm(ds, desc="AudioSet"):
            name = os.path.basename(row["audio"]["path"]).replace(".flac", ".wav")
            scipy.io.wavfile.write(os.path.join(out, name), 16000, (row["audio"]["array"] * 32767).astype(np.int16))

    # 3. Background music: Free Music Archive (30 s clips)
    out = "data/fma"
    if not os.path.isdir(out):
        os.makedirs(out)
        ds = datasets.load_dataset("rudraml/fma", name="small", split="train", streaming=True)
        it = iter(ds.cast_column("audio", datasets.Audio(sampling_rate=16000)))
        for _ in tqdm(range(int(a.fma_hours * 3600 // 30)), desc="FMA"):
            row = next(it)
            name = os.path.basename(row["audio"]["path"]).replace(".mp3", ".wav")
            scipy.io.wavfile.write(os.path.join(out, name), 16000, (row["audio"]["array"] * 32767).astype(np.int16))

    # 4. Pre-computed negative-speech features (~2000 h ACAV100M) + ~11 h false-positive validation set
    base = f"{HF}/davidscripka/openwakeword_features/resolve/main"
    wget(f"{base}/openwakeword_features_ACAV100M_2000_hrs_16bit.npy", "data/openwakeword_features_ACAV100M_2000_hrs_16bit.npy")
    wget(f"{base}/validation_set_features.npy", "data/validation_set_features.npy")
    print("data ready in ./data")


if __name__ == "__main__":
    main()

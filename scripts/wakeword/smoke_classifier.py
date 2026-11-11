#!/usr/bin/env python
"""Train-env smoke test: run openWakeWord's real `Model.auto_train` + `export_model` end to end on
SYNTHETIC embedding features (no TTS, no datasets, CPU, ~1 minute).

It proves the training stack imports and the ONNX export works.  It does NOT prove the model
detects "hey andy": the features are random clusters, not speech.

    python smoke_classifier.py OUT_DIR [--steps 300]
"""
import argparse
import os

import numpy as np
import torch
from openwakeword.train import Model


def loader(x, y, batch):
    class DS(torch.utils.data.IterableDataset):
        def __iter__(self):
            rng = np.random.default_rng(0)
            while True:
                idx = rng.integers(0, len(x), batch)
                yield torch.from_numpy(x[idx]), torch.from_numpy(y[idx])
    return torch.utils.data.DataLoader(DS(), batch_size=None)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out_dir")
    ap.add_argument("--steps", type=int, default=300)
    a = ap.parse_args()
    os.makedirs(a.out_dir, exist_ok=True)
    rng = np.random.default_rng(1)
    shape = (16, 96)
    pattern = rng.normal(0, 1, shape).astype(np.float32)
    pos = (rng.normal(0, 1, (2000, *shape)) * 0.5 + pattern).astype(np.float32)
    neg = rng.normal(0, 1, (8000, *shape)).astype(np.float32)
    x = np.concatenate([pos, neg])
    y = np.concatenate([np.ones(len(pos)), np.zeros(len(neg))]).astype(np.float32)

    # held-out validation, plus a "false-positive" stream shaped (T, 96) like openwakeword's
    vp = (rng.normal(0, 1, (300, *shape)) * 0.5 + pattern).astype(np.float32)
    vn = rng.normal(0, 1, (300, *shape)).astype(np.float32)
    val = torch.utils.data.DataLoader(torch.utils.data.TensorDataset(
        torch.from_numpy(np.concatenate([vp, vn])),
        torch.from_numpy(np.concatenate([np.ones(300), np.zeros(300)]).astype(np.float32))), batch_size=600)
    fp_stream = rng.normal(0, 1, (400, 96)).astype(np.float32)
    fp_win = np.array([fp_stream[i:i + 16] for i in range(0, len(fp_stream) - 16)])
    fp = torch.utils.data.DataLoader(torch.utils.data.TensorDataset(
        torch.from_numpy(fp_win), torch.zeros(len(fp_win))), batch_size=len(fp_win))

    m = Model(n_classes=1, input_shape=shape, model_type="dnn", layer_dim=32,
              seconds_per_example=1280 * shape[0] / 16000)
    best = m.auto_train(X_train=loader(x, y, 256), X_val=val, false_positive_val_data=fp,
                        steps=a.steps, max_negative_weight=100, target_fp_per_hour=0.2)
    m.export_model(model=best, model_name="smoke_classifier", output_dir=a.out_dir)
    print("EXPORTED", os.path.join(a.out_dir, "smoke_classifier.onnx"))


if __name__ == "__main__":
    main()

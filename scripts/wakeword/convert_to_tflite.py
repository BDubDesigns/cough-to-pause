#!/usr/bin/env python
"""Convert an openWakeWord classifier .onnx to a float32 .tflite and prove the result is faithful.

Runs in the *convert* environment (requirements-convert.txt: TensorFlow + onnx2tf).  The training
environment never imports TensorFlow.

    python convert_to_tflite.py hey_andy.onnx hey_andy.tflite

Why not upstream's `train.py --convert_to_tflite`?  It calls `onnx_tf` (unmaintained, needs
TF 2.8 / onnx 1.14 / protobuf 3).  onnx2tf is the maintained converter and works with TF 2.19.

The classifier is a plain MLP (Flatten, Gemm, LayerNorm, ReLU, Sigmoid), so the output must match
onnxruntime to float32 rounding; this script fails (exit 1) if it does not.
"""
import argparse
import os
import shutil
import sys
import tempfile

import numpy as np


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("onnx_path")
    ap.add_argument("tflite_path")
    ap.add_argument("--atol", type=float, default=1e-5)
    args = ap.parse_args()

    import onnx
    import onnx2tf
    import onnxruntime as ort
    from ai_edge_litert.interpreter import Interpreter

    model = onnx.load(args.onnx_path)
    in_shape = [d.dim_value for d in model.graph.input[0].type.tensor_type.shape.dim]
    if in_shape[1:] != [16, 96]:
        print(f"unexpected classifier input shape {in_shape}; Android plan assumes [1,16,96]", file=sys.stderr)
        return 1

    with tempfile.TemporaryDirectory() as tmp:
        # torch names the input "onnx::Flatten_0"; onnx2tf sanitises the "::", so its layout flags
        # (matched by name) silently miss it and it transposes the rank-3 input to [1, 96, 16].
        # Rename to a clean identifier first, then pin the layout.  The parity check below is what
        # catches a regression here.
        old, new = model.graph.input[0].name, "embeddings"
        for node in model.graph.node:
            for i, name in enumerate(node.input):
                if name == old:
                    node.input[i] = new
        model.graph.input[0].name = new
        clean = os.path.join(tmp, "classifier.onnx")
        onnx.save(model, clean)
        onnx2tf.convert(input_onnx_file_path=clean, output_folder_path=os.path.join(tmp, "out"),
                        non_verbose=True,
                        keep_ncw_or_nchw_or_ncdhw_input_names=[new],
                        keep_shape_absolutely_input_names=[new])
        tmp = os.path.join(tmp, "out")
        produced = os.path.join(tmp, "classifier_float32.tflite")
        if not os.path.exists(produced):
            print("onnx2tf produced:", os.listdir(tmp), file=sys.stderr)
            return 1
        shutil.copyfile(produced, args.tflite_path)

    # Numerical parity against onnxruntime on random + structured inputs.
    sess = ort.InferenceSession(args.onnx_path, providers=["CPUExecutionProvider"])
    in_name = sess.get_inputs()[0].name
    it = Interpreter(model_path=args.tflite_path)
    it.allocate_tensors()
    i_d, o_d = it.get_input_details()[0], it.get_output_details()[0]
    print("tflite input ", i_d["shape"].tolist(), np.dtype(i_d["dtype"]).name)
    print("tflite output", o_d["shape"].tolist(), np.dtype(o_d["dtype"]).name)
    rng = np.random.default_rng(0)
    worst = 0.0
    for k in range(200):
        x = (rng.normal(0, 1 + k % 5, size=(1, 16, 96))).astype(np.float32)
        ref = sess.run(None, {in_name: x})[0].reshape(-1)
        it.set_tensor(i_d["index"], x)
        it.invoke()
        got = it.get_tensor(o_d["index"]).reshape(-1)
        worst = max(worst, float(np.abs(ref - got).max()))
    print(f"max |onnxruntime - tflite| over 200 inputs = {worst:.2e}")
    if list(i_d["shape"]) != [1, 16, 96] or worst > args.atol:
        print("FAIL: shape or parity check", file=sys.stderr)
        return 1
    print("OK", args.tflite_path, os.path.getsize(args.tflite_path), "bytes")
    return 0


if __name__ == "__main__":
    sys.exit(main())

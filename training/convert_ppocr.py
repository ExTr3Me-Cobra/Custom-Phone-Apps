"""Fetches the official PaddleOCR PP-OCRv5 mobile recognizer (ONNX, Apache-2.0) and converts it to
a fixed-shape float32 LiteRT model for the app: input [1, 48, 640, 3] (NHWC, values in -1..1),
output [1, 80, 18385] CTC probabilities. Also writes the character dictionary, and fetches the
PP-OCRv5 mobile text detector to build/ppocr/det.onnx (desktop only: the phone-like test uses it
to stand in for ML Kit's whole-page OCR).

    pip install onnx onnx2tf onnx_graphsurgeon onnxsim sng4onnx onnxruntime pyyaml tensorflow-cpu
    python training/convert_ppocr.py
"""
import os
import subprocess
import sys
import urllib.request

import numpy as np

REPO = "https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_rec_onnx/resolve/main"
DET = "https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_det_onnx/resolve/main/inference.onnx"
WORK = os.path.join("build", "ppocr")
OUT = os.path.join("models", "ppocr")


def main():
    os.makedirs(WORK, exist_ok=True)
    os.makedirs(OUT, exist_ok=True)
    for name in ("inference.onnx", "inference.yml"):
        p = os.path.join(WORK, name)
        if not os.path.exists(p):
            urllib.request.urlretrieve(f"{REPO}/{name}", p)
    det = os.path.join(WORK, "det.onnx")
    if not os.path.exists(det):
        urllib.request.urlretrieve(DET, det)
    import yaml
    chars = yaml.safe_load(open(os.path.join(WORK, "inference.yml")))["PostProcess"]["character_dict"]
    with open(os.path.join(OUT, "dict.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(chars) + "\n")

    fixed = os.path.join(WORK, "rec_fixed.onnx")
    subprocess.check_call([sys.executable, "-m", "onnxruntime.tools.make_dynamic_shape_fixed", "--input_name", "x",
                           "--input_shape", "1,3,48,640", os.path.join(WORK, "inference.onnx"), fixed])
    import onnx
    import onnxsim
    model, ok = onnxsim.simplify(onnx.load(fixed))
    assert ok
    static = os.path.join(WORK, "rec_static.onnx")
    onnx.save(model, static)
    subprocess.check_call(["onnx2tf", "-i", static, "-o", os.path.join(WORK, "tfl"), "-n"])
    tfl = os.path.join(WORK, "tfl", "rec_static_float32.tflite")

    # Check the conversion against ONNX Runtime.
    import onnxruntime as ort
    import tensorflow as tf
    x = np.random.default_rng(0).uniform(-1, 1, (1, 3, 48, 640)).astype(np.float32)
    want = ort.InferenceSession(os.path.join(WORK, "inference.onnx")).run(None, {"x": x})[0]
    it = tf.lite.Interpreter(model_path=tfl)
    it.allocate_tensors()
    it.set_tensor(it.get_input_details()[0]["index"], x.transpose(0, 2, 3, 1))
    it.invoke()
    got = it.get_tensor(it.get_output_details()[0]["index"])
    err = float(np.abs(want - got).max())
    assert err < 1e-3, err
    os.replace(tfl, os.path.join(OUT, "rec.tflite"))
    print(f"models/ppocr/rec.tflite written; max difference vs ONNX {err:.2e}")


if __name__ == "__main__":
    main()

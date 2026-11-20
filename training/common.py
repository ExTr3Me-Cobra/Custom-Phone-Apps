"""Shared helpers: dataset loading (.lrd from :mltools), export to LiteRT (.tflite) and to the
.lrw weights the JVM reference implementation reads, plus test vectors that tie the two together."""
import json
import os
import struct

import numpy as np
import tensorflow as tf
from tensorflow import keras


def load_lrd(path):
    """Returns (header, x uint8 [N,H,W], y uint8 [N,...])."""
    with open(path, "rb") as f:
        b = f.read()
    assert b[:4] == b"LRD1", path
    n = struct.unpack("<i", b[4:8])[0]
    h = json.loads(b[8:8 + n])
    off = 8 + n
    cnt = h["count"]
    xs = h["h"] * h["w"] * h["c"]
    ls = int(np.prod(h["label"]))
    x = np.frombuffer(b, np.uint8, cnt * xs, off).reshape(cnt, h["h"], h["w"], h["c"])
    y = np.frombuffer(b, np.uint8, cnt * ls, off + cnt * xs).reshape((cnt,) + tuple(h["label"]))
    return h, x, y


def load_parts(data_dir, prefix):
    parts = sorted(p for p in os.listdir(data_dir) if p.startswith(prefix) and p.endswith(".lrd"))
    loaded = [load_lrd(os.path.join(data_dir, p)) for p in parts]
    return loaded[0][0], np.concatenate([l[1] for l in loaded]), np.concatenate([l[2] for l in loaded])


def _act(layer):
    a = getattr(layer, "activation", None)
    name = getattr(a, "__name__", "linear") if a is not None else "linear"
    return {"relu": "relu", "sigmoid": "sigmoid", "softmax": "softmax", "linear": "linear"}[name]


def export_lrw(model, path, residual=False, meta=None):
    """Sequential conv/pool/flatten/dense nets only (what the Kotlin RefNet supports)."""
    layers, blob = [], []
    offset = 0

    def put(arr):
        nonlocal offset
        a = np.asarray(arr, np.float32).ravel()
        blob.append(a)
        o = offset
        offset += a.size
        return o

    for l in model.layers:
        if isinstance(l, keras.layers.Conv2D):
            k, b = l.get_weights()
            assert l.padding == "same" and k.shape[0] == k.shape[1]
            layers.append({"type": "conv", "k": int(k.shape[0]), "stride": int(l.strides[0]), "in": int(k.shape[2]),
                           "out": int(k.shape[3]), "act": _act(l), "w": put(k), "b": put(b)})
        elif isinstance(l, keras.layers.MaxPooling2D):
            layers.append({"type": "maxpool"})
        elif isinstance(l, keras.layers.Flatten):
            layers.append({"type": "flatten"})
        elif isinstance(l, keras.layers.Dense):
            w, b = l.get_weights()
            layers.append({"type": "dense", "in": int(w.shape[0]), "out": int(w.shape[1]), "act": _act(l), "w": put(w), "b": put(b)})
        elif isinstance(l, (keras.layers.Dropout, keras.layers.InputLayer, keras.layers.Add, keras.layers.GaussianNoise)):
            continue
        else:
            raise ValueError(f"unsupported layer {l}")
    spec = {"input": [int(d) for d in model.input_shape[1:]], "layers": layers, "residual": residual, "meta": meta or {}}
    js = json.dumps(spec).encode()
    with open(path, "wb") as f:
        f.write(b"LRW1")
        f.write(struct.pack("<i", len(js)))
        f.write(js)
        f.write(np.concatenate(blob).astype("<f4").tobytes())


def export_tflite(model, path, input_shape):
    """Fixed-shape (batch 1) float32 LiteRT model with the weights embedded as constants
    (works with the GPU delegate and the CPU interpreter). Saved as a SavedModel first so the
    converter freezes the variables."""
    import tempfile

    class Serving(tf.Module):
        def __init__(self, m):
            super().__init__()
            self.m = m

        @tf.function(input_signature=[tf.TensorSpec([1] + list(input_shape), tf.float32)])
        def serve(self, x):
            return self.m(x, training=False)

    mod = Serving(model)
    with tempfile.TemporaryDirectory() as d:
        tf.saved_model.save(mod, d, signatures={"serving_default": mod.serve})
        conv = tf.lite.TFLiteConverter.from_saved_model(d)
        data = conv.convert()
    with open(path, "wb") as f:
        f.write(data)
    # The export must reproduce the Keras model.
    x = np.random.default_rng(0).random((2,) + tuple(input_shape)).astype(np.float32)
    want = model(x, training=False).numpy()
    got = tflite_run(path, x)
    err = float(np.max(np.abs(want - got)))
    assert np.isfinite(err) and err < 1e-3, f"LiteRT export differs from Keras by {err}"
    return len(data)


def tflite_run(path, x):
    it = tf.lite.Interpreter(model_path=path)
    it.allocate_tensors()
    inp = it.get_input_details()[0]
    out = it.get_output_details()[0]
    res = []
    for s in x:
        it.set_tensor(inp["index"], s[None].astype(np.float32))
        it.invoke()
        res.append(it.get_tensor(out["index"])[0])
    return np.stack(res)


def write_testvec(path, tflite_path, x):
    """A couple of inputs and the LiteRT outputs, for the Kotlin RefNet equivalence test."""
    y = tflite_run(tflite_path, x)
    with open(path, "w") as f:
        json.dump({"input_shape": list(x.shape[1:]), "inputs": [a.astype(np.float64).ravel().round(6).tolist() for a in x],
                   "outputs": [a.astype(np.float64).ravel().round(6).tolist() for a in y]}, f)


def save_keras(model, out_dir, name):
    """Keeps the trained Keras model (outside the repo) so exports can be redone without retraining."""
    d = os.environ.get("KERAS_DIR", os.path.join(out_dir, "..", "build", "keras"))
    os.makedirs(d, exist_ok=True)
    model.save(os.path.join(d, name + ".keras"))


def save_metrics(out_dir, name, metrics):
    p = os.path.join(out_dir, "TRAINING.json")
    data = json.load(open(p)) if os.path.exists(p) else {}
    data[name] = metrics
    with open(p, "w") as f:
        json.dump(data, f, indent=2, sort_keys=True)

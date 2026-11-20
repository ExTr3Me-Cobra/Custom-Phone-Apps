"""Restoration (denoise/deblur): tiny residual fully-convolutional CNN, trained on aligned
(damaged, clean) patches; exported with a fixed 96x96 tile shape for LiteRT."""
import argparse
import os

import numpy as np
import tensorflow as tf
from tensorflow import keras

import common

TILE = 96


def build(shape):
    inp = keras.Input(shape)
    x = inp
    for _ in range(3):
        x = keras.layers.Conv2D(16, 3, padding="same", activation="relu")(x)
    x = keras.layers.Conv2D(1, 3, padding="same", activation="linear")(x)
    out = keras.layers.Add()([inp, x])
    return keras.Model(inp, out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--epochs", type=int, default=15)
    ap.add_argument("--export-only", action="store_true", help="re-export the saved Keras model without training")
    a = ap.parse_args()
    tf.keras.utils.set_random_seed(2)
    _, xt, yt = common.load_lrd(os.path.join(a.data, "restore_train.lrd"))
    _, xv, yv = common.load_lrd(os.path.join(a.data, "restore_val.lrd"))
    xt = xt.astype(np.float32) / 255; yt = yt.astype(np.float32) / 255
    xv = xv.astype(np.float32) / 255; yv = yv.astype(np.float32) / 255
    m = build((None, None, 1))
    m.compile(optimizer=keras.optimizers.Adam(1e-3), loss="mae")
    if a.export_only:
        saved = keras.models.load_model(common.keras_path(a.out, "restore"), compile=False)
        m.set_weights(saved.get_weights())
        m.compile(optimizer="adam", loss="mae")
    else:
        m.fit(xt, yt, validation_data=(xv, yv), epochs=a.epochs, batch_size=64, verbose=2,
              callbacks=[keras.callbacks.ReduceLROnPlateau(patience=2, factor=0.5), keras.callbacks.EarlyStopping(patience=4, restore_best_weights=True)])
    base = float(np.mean(np.abs(xv - yv)))
    val = float(m.evaluate(xv, yv, verbose=0))
    fixed = build((TILE, TILE, 1))
    fixed.set_weights(m.get_weights())
    os.makedirs(a.out, exist_ok=True)
    if not a.export_only:
        common.save_keras(fixed, a.out, "restore")
    tfl = os.path.join(a.out, "restore.tflite")
    size = common.export_tflite(fixed, tfl, (TILE, TILE, 1))
    common.export_lrw(fixed, os.path.join(a.out, "restore.lrw"), residual=True, meta={"tile": TILE})
    tiles = np.zeros((2, TILE, TILE, 1), np.float32)
    tiles[:, :64, :64] = xv[:2]
    common.write_testvec(os.path.join(a.out, "restore.testvec.json"), tfl, tiles)
    common.save_metrics(a.out, "restore", {"val_mae": round(val, 5), "identity_mae": round(base, 5), "tflite_bytes": size, "params": int(m.count_params())})
    print(f"restore: val MAE {val:.4f} (input MAE {base:.4f}), {size / 1024:.0f} KiB")


if __name__ == "__main__":
    main()

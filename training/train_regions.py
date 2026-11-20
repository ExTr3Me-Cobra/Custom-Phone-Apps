"""Region detector: 256x256 label image -> 32x32x4 masks (barcode, tracking number text, ZIP,
service banner). Small fully-convolutional CNN; boxes come from connected components on device."""
import argparse
import os

import numpy as np
import tensorflow as tf
from tensorflow import keras

import common

KINDS = ["barcode", "tracking_text", "zip", "service_banner"]


def build():
    return keras.Sequential([
        keras.Input((256, 256, 1)),
        keras.layers.Conv2D(16, 3, strides=2, padding="same", activation="relu"),
        keras.layers.Conv2D(16, 3, padding="same", activation="relu"),
        keras.layers.Conv2D(32, 3, strides=2, padding="same", activation="relu"),
        keras.layers.Conv2D(32, 3, padding="same", activation="relu"),
        keras.layers.Conv2D(48, 3, strides=2, padding="same", activation="relu"),
        keras.layers.Conv2D(48, 3, padding="same", activation="relu"),
        keras.layers.Conv2D(48, 3, padding="same", activation="relu"),
        keras.layers.Conv2D(48, 3, padding="same", activation="relu"),
        keras.layers.Conv2D(4, 1, padding="same", activation="sigmoid"),
    ])


def weighted_bce(y, p):
    p = tf.clip_by_value(p, 1e-6, 1 - 1e-6)
    return -tf.reduce_mean(4.0 * y * tf.math.log(p) + (1 - y) * tf.math.log(1 - p))


def iou(y, p):
    pb = p > 0.5
    yb = y > 0.5
    inter = np.logical_and(pb, yb).sum(axis=(0, 1, 2))
    union = np.logical_or(pb, yb).sum(axis=(0, 1, 2))
    return {k: round(float(i / max(u, 1)), 3) for k, i, u in zip(KINDS, inter, union)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--epochs", type=int, default=20)
    ap.add_argument("--export-only", action="store_true", help="re-export the saved Keras model without training")
    a = ap.parse_args()
    tf.keras.utils.set_random_seed(3)
    _, xt, yt = common.load_parts(a.data, "regions_train")
    _, xv, yv = common.load_lrd(os.path.join(a.data, "regions_val.lrd"))
    xt = xt.astype(np.float32) / 255; xv = xv.astype(np.float32) / 255
    yt = yt.astype(np.float32); yv = yv.astype(np.float32)
    m = build()
    m.compile(optimizer=keras.optimizers.Adam(2e-3), loss=weighted_bce)
    if a.export_only:
        m = keras.models.load_model(common.keras_path(a.out, "region_detector"), compile=False)
        m.compile(optimizer="adam", loss=weighted_bce)
    else:
        m.fit(xt, yt, validation_data=(xv, yv), epochs=a.epochs, batch_size=32, verbose=2,
              callbacks=[keras.callbacks.ReduceLROnPlateau(patience=2, factor=0.5), keras.callbacks.EarlyStopping(patience=4, restore_best_weights=True)])
    pv = m.predict(xv, verbose=0)
    ious = iou(yv, pv)
    os.makedirs(a.out, exist_ok=True)
    if not a.export_only:
        common.save_keras(m, a.out, "region_detector")
    tfl = os.path.join(a.out, "region_detector.tflite")
    size = common.export_tflite(m, tfl, (256, 256, 1))
    common.export_lrw(m, os.path.join(a.out, "region_detector.lrw"), meta={"kinds": KINDS, "stride": 8})
    common.write_testvec(os.path.join(a.out, "region_detector.testvec.json"), tfl, xv[:1])
    common.save_metrics(a.out, "region_detector", {"val_iou": ious, "tflite_bytes": size, "params": int(m.count_params())})
    print(f"region_detector: IoU {ious}, {size / 1024:.0f} KiB")


if __name__ == "__main__":
    main()

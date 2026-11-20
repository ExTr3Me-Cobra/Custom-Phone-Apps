"""Damaged-digit classifier: 32x32 crop -> probabilities for 0-9. Small CNN (~170k params)."""
import argparse
import os

import numpy as np
import tensorflow as tf
from tensorflow import keras

import common


def build():
    m = keras.Sequential([
        keras.Input((32, 32, 1)),
        keras.layers.Conv2D(16, 3, padding="same", activation="relu"),
        keras.layers.Conv2D(16, 3, padding="same", activation="relu"),
        keras.layers.MaxPooling2D(),
        keras.layers.Conv2D(32, 3, padding="same", activation="relu"),
        keras.layers.Conv2D(32, 3, padding="same", activation="relu"),
        keras.layers.MaxPooling2D(),
        keras.layers.Conv2D(64, 3, padding="same", activation="relu"),
        keras.layers.MaxPooling2D(),
        keras.layers.Flatten(),
        keras.layers.Dropout(0.3),
        keras.layers.Dense(128, activation="relu"),
        keras.layers.Dropout(0.3),
        keras.layers.Dense(10, activation="softmax"),
    ])
    return m


def load_extra(folder):
    """Crops exported from the app (Settings > Export crops): 32x32 PNGs, dark ink on light.
    Returns images, digits, trusted flags (USPS-confirmed numbers) and the label each came from."""
    import csv
    xs, ys, trusted, labels = [], [], [], []
    with open(os.path.join(folder, "labels.csv")) as f:
        for row in csv.DictReader(f):
            p = os.path.join(folder, row["file"])
            if not os.path.exists(p) or not row.get("digit", "").isdigit():
                continue
            png = tf.io.decode_png(tf.io.read_file(p), channels=1).numpy()
            xs.append(1.0 - png.astype(np.float32) / 255)
            ys.append(int(row["digit"]))
            trusted.append(row.get("trusted") == "1")
            labels.append(row.get("tracking") or row["file"].split("_")[0])
    return np.stack(xs), np.array(ys, np.int32), np.array(trusted), labels


def split_by_label(labels, fraction=0.2):
    """Hold out whole labels (never crops of one label on both sides), deterministically."""
    import zlib
    return np.array([zlib.crc32(l.encode()) % 100 < fraction * 100 for l in labels])


def tflite_accuracy(path, x, y):
    if not os.path.exists(path) or len(y) == 0:
        return None
    p = common.tflite_run(path, x)
    return float((p.argmax(1) == y).mean())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--epochs", type=int, default=12)
    ap.add_argument("--extra", help="folder of real crops exported from the app (labels.csv + PNGs)")
    ap.add_argument("--force", action="store_true", help="replace the model even if it's worse on the held-out real crops")
    ap.add_argument("--export-only", action="store_true", help="re-export the saved Keras model without training")
    a = ap.parse_args()
    tf.keras.utils.set_random_seed(1)
    _, xt, yt = common.load_lrd(os.path.join(a.data, "digits_train.lrd"))
    _, xv, yv = common.load_lrd(os.path.join(a.data, "digits_val.lrd"))
    xt = xt.astype(np.float32) / 255; xv = xv.astype(np.float32) / 255
    yt = yt[:, 0].astype(np.int32); yv = yv[:, 0].astype(np.int32)
    real_test = None
    if a.extra:
        xe, ye, te, le = load_extra(a.extra)
        hold = split_by_label(le)
        real_test = (xe[hold], ye[hold])
        tr = ~hold
        # Trusted (USPS-confirmed) crops count 3x, the rest once: a wrongly confirmed label
        # can't outweigh the synthetic data.
        xs = [xt, xe[tr]] + [xe[tr & te]] * 2
        ys_ = [yt, ye[tr]] + [ye[tr & te]] * 2
        xt = np.concatenate(xs); yt = np.concatenate(ys_)
        print(f"real crops: {len(ye)} ({int(te.sum())} trusted), {int(tr.sum())} for training, {int(hold.sum())} held out (whole labels)")
    m = build()
    m.compile(optimizer=keras.optimizers.Adam(2e-3), loss="sparse_categorical_crossentropy", metrics=["accuracy"])
    if a.export_only:
        m = keras.models.load_model(common.keras_path(a.out, "digit_classifier"), compile=False)
        m.compile(optimizer="adam", loss="sparse_categorical_crossentropy", metrics=["accuracy"])
    else:
        m.fit(xt, yt, validation_data=(xv, yv), epochs=a.epochs, batch_size=128, verbose=2,
              callbacks=[keras.callbacks.ReduceLROnPlateau(patience=2, factor=0.5), keras.callbacks.EarlyStopping(patience=4, restore_best_weights=True)])
    loss, acc = m.evaluate(xv, yv, verbose=0)
    # Calibration: mean probability assigned to the true digit.
    p = m.predict(xv, verbose=0)
    true_p = float(np.mean(p[np.arange(len(yv)), yv]))
    os.makedirs(a.out, exist_ok=True)
    # On the held-out real crops, the new model must not be worse than the current one.
    old_path = os.path.join(a.out, "digit_classifier.tflite")
    if real_test is not None and len(real_test[1]) > 0:
        tmp = os.path.join(a.out, "digit_classifier.candidate.tflite")
        common.export_tflite(m, tmp, (32, 32, 1))
        new_acc = tflite_accuracy(tmp, *real_test)
        old_acc = tflite_accuracy(old_path, *real_test)
        os.remove(tmp)
        print(f"held-out real crops: new model {new_acc:.3f}, current model {old_acc if old_acc is None else round(old_acc, 3)}")
        if old_acc is not None and new_acc < old_acc and not a.force:
            print("New model is worse on your real labels; keeping the current model (use --force to override).")
            return
    if not a.export_only:
        common.save_keras(m, a.out, "digit_classifier")
    tfl = os.path.join(a.out, "digit_classifier.tflite")
    size = common.export_tflite(m, tfl, (32, 32, 1))
    common.export_lrw(m, os.path.join(a.out, "digit_classifier.lrw"), meta={"classes": 10})
    common.write_testvec(os.path.join(a.out, "digit_classifier.testvec.json"), tfl, xv[:3])
    common.save_metrics(a.out, "digit_classifier", {"val_accuracy": round(float(acc), 4), "val_loss": round(float(loss), 4),
                                                   "mean_p_true": round(true_p, 4), "tflite_bytes": size, "params": int(m.count_params())})
    print(f"digit_classifier: val acc {acc:.4f}, {size / 1024:.0f} KiB")


if __name__ == "__main__":
    main()

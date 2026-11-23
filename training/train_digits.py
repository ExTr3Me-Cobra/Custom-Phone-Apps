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


def shipper(tracking):
    """The shipper behind a USPS number: its Mailer ID (IMpb: channel 2 + service 3, then 9 digits
    if they start with 9, else 6). Same shipper means the same printer, font and layout."""
    t = "".join(c for c in tracking if c.isdigit())
    if len(t) >= 20 and t[:2] in ("92", "93", "94", "95"):
        return t[5:14] if t[5] == "9" else t[5:11]
    return t[:11]


# A shipper's crops together count like at most this many labels, so one shipper scanned over and
# over can't dominate the real data (its extra labels still help, at a lower weight each).
LABELS_PER_SHIPPER = 5


def shipper_weights(labels):
    """Per crop: min(1, LABELS_PER_SHIPPER / labels from the same shipper)."""
    from collections import defaultdict
    per = defaultdict(set)
    for l in labels:
        per[shipper(l)].add(l)
    return np.array([min(1.0, LABELS_PER_SHIPPER / len(per[shipper(l)])) for l in labels], np.float32)


def split_by_label(labels, fraction=0.2):
    """Hold out whole labels (never crops of one label on both sides), deterministically."""
    import zlib
    return np.array([zlib.crc32(l.encode()) % 100 < fraction * 100 for l in labels])


def tflite_accuracy(path, x, y, w=None):
    """Accuracy, weighted by [w] if given (each shipper counts about equally)."""
    if not os.path.exists(path) or len(y) == 0:
        return None
    p = common.tflite_run(path, x)
    ok = (p.argmax(1) == y).astype(np.float32)
    return float(ok.mean() if w is None else (ok * w).sum() / w.sum())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--epochs", type=int, default=12)
    ap.add_argument("--extra", help="folder of real crops exported from the app (labels.csv + PNGs)")
    ap.add_argument("--damaged-test", help="folder of real damaged digits (labels.csv + PNGs): test only, never trained on")
    ap.add_argument("--force", action="store_true", help="replace the model even if it's worse on the held-out real crops")
    ap.add_argument("--export-only", action="store_true", help="re-export the saved Keras model without training")
    a = ap.parse_args()
    tf.keras.utils.set_random_seed(1)
    _, xt, yt = common.load_lrd(os.path.join(a.data, "digits_train.lrd"))
    _, xv, yv = common.load_lrd(os.path.join(a.data, "digits_val.lrd"))
    xt = xt.astype(np.float32) / 255; xv = xv.astype(np.float32) / 255
    yt = yt[:, 0].astype(np.int32); yv = yv[:, 0].astype(np.int32)
    real_test = None
    weights = None
    damaged_test = None
    test_labels = set()
    if a.damaged_test and os.path.exists(os.path.join(a.damaged_test, "labels.csv")):
        xd, yd, _, ld = load_extra(a.damaged_test)
        damaged_test = (xd, yd, None)
        test_labels = set(ld)
        print(f"real damaged test digits: {len(yd)} from {len(test_labels)} labels (never trained on)")
    if a.extra:
        xe, ye, te, le = load_extra(a.extra)
        # The damaged test's labels stay out of training entirely.
        keep = np.array([l not in test_labels for l in le])
        xe, ye, te, le = xe[keep], ye[keep], te[keep], [l for l, k in zip(le, keep) if k]
        hold = split_by_label(le)
        sw = shipper_weights(le)
        real_test = (xe[hold], ye[hold], sw[hold])
        tr = ~hold
        # Trusted crops (USPS-confirmed, or labeled by an intact barcode) count 3x, the rest once,
        # so a wrongly confirmed label can't outweigh the synthetic data; and each shipper's
        # crops together count like at most LABELS_PER_SHIPPER labels.
        w_real = sw[tr] * np.where(te[tr], 3.0, 1.0)
        xt = np.concatenate([xt, xe[tr]]); yt = np.concatenate([yt, ye[tr]])
        weights = np.concatenate([np.ones(len(yt) - int(tr.sum()), np.float32), w_real])
        print(f"real crops: {len(ye)} ({int(te.sum())} trusted) from {len({shipper(l) for l in le})} shippers, "
              f"{int(tr.sum())} for training, {int(hold.sum())} held out (whole labels)")
        # Real crops with real-world damage (from :mltools:datasets), minus held-out and test labels.
        dmg = os.path.join(a.data, "digits_realdmg.lrd")
        if os.path.exists(dmg):
            _, xr, yr = common.load_lrd(dmg)
            tracks = open(os.path.join(a.data, "digits_realdmg.tracking.txt")).read().split("\n")
            held = {l for l, h in zip(le, hold) if h}
            ok = np.array([t not in held and t not in test_labels for t in tracks[:len(yr)]])
            xr = xr[ok].astype(np.float32) / 255; yr = yr[ok][:, 0].astype(np.int32)
            xt = np.concatenate([xt, xr]); yt = np.concatenate([yt, yr])
            weights = np.concatenate([weights, np.full(len(yr), 0.5, np.float32)])
            print(f"real crops with real-world damage: {len(yr)} for training (weight 0.5 each)")
    m = build()
    m.compile(optimizer=keras.optimizers.Adam(2e-3), loss="sparse_categorical_crossentropy", metrics=["accuracy"])
    if a.export_only:
        m = keras.models.load_model(common.keras_path(a.out, "digit_classifier"), compile=False)
        m.compile(optimizer="adam", loss="sparse_categorical_crossentropy", metrics=["accuracy"])
    else:
        m.fit(xt, yt, sample_weight=weights, validation_data=(xv, yv), epochs=a.epochs, batch_size=128, verbose=2,
              callbacks=[keras.callbacks.ReduceLROnPlateau(patience=2, factor=0.5), keras.callbacks.EarlyStopping(patience=4, restore_best_weights=True)])
    loss, acc = m.evaluate(xv, yv, verbose=0)
    # Calibration: mean probability assigned to the true digit.
    p = m.predict(xv, verbose=0)
    true_p = float(np.mean(p[np.arange(len(yv)), yv]))
    os.makedirs(a.out, exist_ok=True)
    # The new model must not be worse than the current one: on the held-out real crops (clean),
    # and on the real damaged digits (never trained on).
    old_path = os.path.join(a.out, "digit_classifier.tflite")
    if (real_test is not None and len(real_test[1]) > 0) or damaged_test is not None:
        tmp = os.path.join(a.out, "digit_classifier.candidate.tflite")
        common.export_tflite(m, tmp, (32, 32, 1))
        reject = None
        if real_test is not None and len(real_test[1]) > 0:
            new_acc = tflite_accuracy(tmp, *real_test)
            old_acc = tflite_accuracy(old_path, *real_test)
            print(f"held-out real crops: new model {new_acc:.3f}, current model {old_acc if old_acc is None else round(old_acc, 3)}")
            # The curated real crops were checked with the current model (a crop is kept only where it
            # reads as its label), so it scores near 100% on them by construction: a new model may
            # trail it by up to 2 points.
            if old_acc is not None and new_acc < old_acc - 0.02:
                reject = "worse on your clean real crops"
        if damaged_test is not None:
            new_d = tflite_accuracy(tmp, *damaged_test)
            old_d = tflite_accuracy(old_path, *damaged_test)
            print(f"real damaged digits: new model {new_d:.3f}, current model {old_d if old_d is None else round(old_d, 3)}")
            if old_d is not None and new_d < old_d:
                reject = reject or "reads fewer real damaged digits"
        os.remove(tmp)
        if reject and not a.force:
            print(f"New model {reject}; keeping the current model (use --force to override).")
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

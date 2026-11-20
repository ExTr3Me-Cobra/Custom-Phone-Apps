#!/usr/bin/env bash
# Generates synthetic data, trains the three models on CPU, exports .tflite (+ .lrw for the JVM
# benchmark), then runs the with/without benchmark, which writes models/manifest.json and keeps
# only the models that measurably improve recovery.
#   training/train_all.sh [scale]   (scale 1.0 = full size; 0.1 for a quick smoke run)
set -euo pipefail
cd "$(dirname "$0")/.."
SCALE="${1:-1.0}"
DATA="${DATA_DIR:-build/datasets}"
OUT="models"
PY="${PYTHON:-python3}"
./gradlew --no-daemon :mltools:datasets -Pout="$PWD/$DATA" -Pscale="$SCALE"
EP_SCALE=$("$PY" -c "print(1 if float('$SCALE') >= 0.5 else 0)")
EPOCHS_ARGS=()
if [ "$EP_SCALE" = "0" ]; then EPOCHS_ARGS=(--epochs 3); fi
"$PY" training/train_digits.py --data "$DATA" --out "$OUT" "${EPOCHS_ARGS[@]}"
"$PY" training/train_restore.py --data "$DATA" --out "$OUT" "${EPOCHS_ARGS[@]}"
"$PY" training/train_regions.py --data "$DATA" --out "$OUT" "${EPOCHS_ARGS[@]}"
./gradlew --no-daemon :mltools:benchmark -Pcases="${CASES:-96}"
cat "$OUT/manifest.json"

# Training the on-device models

Three small models, trained on CPU from synthetic damaged-label data. No API keys, no downloads
at runtime. They are bundled in the APK as LiteRT (`.tflite`) files.

| Model | Input → output | Size | Used for |
|---|---|---|---|
| `digit_classifier` | 32×32 character crop → P(0…9) | ~660 KiB | A fourth evidence source in the per-position fusion, next to OCR and barcode |
| `restore` | 96×96 tile → restored tile | ~22 KiB | An extra preprocessing variant before barcode and OCR decoding |
| `region_detector` | 256×256 label → 32×32×4 masks | ~370 KiB | Finds the barcode, tracking-number text, ZIPs and service banner for automatic crops |

How the digit classifier works:

* It reads every position of the printed number, including positions OCR missed. Their boxes are
  estimated from the character pitch.
* The OCR crops and the training crops are cut by the same code (`CharCrop` in `core`).

## Data

`:mltools` renders synthetic USPS labels with Java2D, using every installed label-like font
(sans, mono, condensed, with and without anti-aliasing). It then damages them:

* partial strokes and missing ink
* smudges
* blur
* creases
* thermal-print fading and printhead streaks
* pixelation
* noise and uneven light

```sh
./gradlew :mltools:datasets -Pout=$PWD/build/datasets -Pscale=1.0   # -Pwhich=digits,restore,regions
```

## Train, export, benchmark

```sh
pip install -r training/requirements.txt
training/train_all.sh 1.0     # data + all three models + benchmark (about 1-2 h on 4 CPU cores)
```

Each `train_*.py`:

1. Trains a small Keras CNN.
2. Exports it to `models/<name>.tflite` (weights frozen, batch 1, float32). The export is checked
   against Keras.
3. Writes `models/<name>.lrw` and `<name>.testvec.json`. The `.lrw` file holds the same weights
   for the pure-Kotlin reference implementation the JVM benchmark uses.
   `./gradlew :mltools:test` checks that this reference matches LiteRT on the test vectors.

`--export-only` re-exports a saved Keras model without retraining.

`./gradlew :mltools:benchmark -Pcases=96` runs the full recovery pipeline on rendered, physically
damaged, twice-photographed labels:

* ZXing, the partial Code 128 decoder, and a simulated OCR that fails where the ink is damaged
* first without models, then with each model on its own

It keeps a model only if it fixes clearly more cases than it breaks: a net gain of at least 3% of
the cases (and at least 2), with neither top-1 nor top-3 lower. It writes `models/manifest.json`,
which the app reads so it loads only the kept models, and `models/BENCHMARK.md`.

On a phone, `./gradlew :app:connectedDebugAndroidTest` runs the same with/without comparison with
the real ML Kit OCR and LiteRT.

## GitHub Actions

`.github/workflows/train-models.yml` runs all of this on a CPU runner. It publishes the `.tflite`
files, `manifest.json` and the metrics as a `models-N` release and as an artifact, and commits
them to the branch so the next app build bundles them. You can start it in two ways:

* the **Run workflow** button, once the workflow is on the default branch
* a push whose commit message contains `[train models]`

## Real crops from your own labels (optional)

In the app, Settings > **Keep digit crops of confirmed labels** saves each digit's crop when you
mark a photo result as confirmed. The digit is known once you confirm it. Nothing is uploaded.
**Export crops** gives a ZIP with `labels.csv` and PNGs. Unzip it and retrain with:

```sh
python training/train_digits.py --data build/datasets --out models --extra path/to/training_crops
```

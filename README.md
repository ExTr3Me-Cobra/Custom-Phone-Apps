# Label Rescue

An Android app that recovers full USPS tracking numbers from damaged, smudged, torn or partly
printed shipping labels. It works 100% offline, with no AI services or API keys. Accuracy comes
first, then speed.

## Install the APK

Every push builds a debug APK. It is published two ways.

**Releases (simplest on a phone).** Open this repository's **Releases** page and pick the newest
**Label Rescue (build N)** pre-release. Tap **LabelRescue-debug.apk** to download it, then open
it to install.

**Actions artifact.**

1. On GitHub, open this repository and tap the **Actions** tab.
2. Open the newest **Build** run with a green check mark (on the branch you want).
3. Scroll down to **Artifacts** and download **LabelRescue-debug-apk**. GitHub downloads it as a
   `.zip` file. You need to be signed in to GitHub to download artifacts.
4. Unzip it to get `LabelRescue-debug.apk`. On a phone, the Files app can unzip it; on a computer,
   unzip it and copy the APK to the phone.
5. Open the APK on the phone. If Android says installing unknown apps is blocked, tap
   **Settings** in that message and allow your browser or Files app to install apps. On Samsung
   phones, if **Auto Blocker** stops the install, turn it off in Settings > Security and privacy >
   Auto Blocker, install, then turn it back on.

A debug build is signed with the build machine's temporary debug key. A newer debug build from a
different run may not install over an older one ("App not installed"). If that happens, uninstall
the old one first. Export your history first (History > Export) if you want to keep it. Signed
release builds (below) don't have this problem.

GitHub keeps artifacts for 90 days by default.

### Signed release builds (optional)

The workflow's second job builds a signed release APK and AAB (artifact **LabelRescue-release**).
It skips itself, with a notice, when these repository secrets are missing (Settings > Secrets
and variables > Actions):

| Secret | Value |
|---|---|
| `LABELRESCUE_KEYSTORE_BASE64` | the keystore file, base64-encoded |
| `LABELRESCUE_KEYSTORE_PASSWORD` | keystore password |
| `LABELRESCUE_KEY_ALIAS` | key alias |
| `LABELRESCUE_KEY_PASSWORD` | key password |

Make a keystore once, and keep it safe. Every future update must be signed with the same key.

```sh
keytool -genkeypair -v -keystore labelrescue.keystore -alias labelrescue \
  -keyalg RSA -keysize 2048 -validity 36500
base64 -w0 labelrescue.keystore   # paste the output into LABELRESCUE_KEYSTORE_BASE64
```

Never commit the keystore. `*.keystore` and `*.jks` are in `.gitignore`.

## Using it

* **Scan label (burst):** fill the frame with the barcode and the number under it, tap to focus,
  then tap **Burst capture**. The app grabs 12 frames from the camera stream plus 2
  full-resolution stills. Turn on the torch for dim light.
* **Import photos:** pick one or more photos of the same label.
* **Crop (optional):** drag a box around the barcode and/or around the printed number. The boxes
  apply to every frame. Skip this step to use the whole photo; the barcode is found
  automatically.
* **Type digits:** type what you can read, with `?` for each unreadable digit. Spaces are fine.
  `[38]` means "3 or 8". This works without any photo. Example: `9234 ?902 7507 ?500 2117 09`.

Results show the best guess in large digits grouped 4-4-4-4-4-2:

* Normal digits are certain. Amber digits are likely, red digits are guessed, and blue digits
  are ones you picked.
* Tap any digit to see a zoomed crop of that spot on the label and pick a different digit. The
  list re-ranks right away.
* Below that are the top candidates, each with a confidence %. Every candidate has a valid check
  digit.
* **Copy**, **Share** and **Mark as confirmed** buttons. Confirmed numbers go into the local
  history, and their Mailer IDs and service codes help rank future labels.
* **Check online** (optional, see below): verify candidates with USPS, or open them on USPS.com.

## Online verification (optional)

Recovery never needs the network. If you add your own free USPS developer credentials, the app
can also check candidates against the official USPS Tracking API (developers.usps.com). It never
scrapes usps.com.

1. On developers.usps.com, create an app that includes the **Tracking** API. Copy its
   **Consumer Key** and **Consumer Secret**.
2. In Label Rescue, open **Settings**, turn on **USPS verification**, paste the key and secret,
   and Save. They are stored with EncryptedSharedPreferences.
3. After the next recovery, the app asks you to confirm or fix what it read from the label:
   * destination ZIP and city
   * origin ZIP and city
   * mail class
   * label date

   These decide the match, so check them.
4. Candidates are then looked up in rank order, best first. The search stops at the first
   **confirmed** match.

Each USPS record is compared with the label:

| Result | Meaning | Effect on ranking |
|---|---|---|
| **Confirmed** | Tracking history exists and matches the label: the destination ZIP matches, or two of origin, cities, mail class, and the label date (within 4 days of the first scan). Nothing conflicts. | Moves to the top |
| **Different package** | Real tracking history, but a detail conflicts (another ZIP, mail class, or a date more than 10 days off) | Ranked far down |
| **Not found** | USPS has no record | Ranked down |
| **Unknown** | Network error, rate limited, no credentials, the label has no scans yet, or nothing on the label to compare | No change |

The evidence is shown under each candidate, for example "Destination 05641 matches, Origin 08081
matches".

Details:

* **API version:** Tracking 3.2 (`v3r2`) is the current API. Tracking 3.0 is closed to new users
  and retires on July 31, 2027. The app tries the 3.2 forms first and remembers the one that
  works.
* **Sign-in token:** the OAuth token is cached until it expires.
* **Caching:** every answer is saved locally (Room), so a number is never looked up twice.
  Settings has **Clear saved USPS results** to re-check, for example a label that had no scans
  yet.
* **Rate limits:** USPS doesn't publish its default quota. The app limits itself to a
  configurable number of requests per hour (default 60). On 429 or server errors it backs off
  exponentially and honors `Retry-After`.
* **Not-found errors:** these follow the USPS Error Inventory. Codes 150002 and similar mean the
  number was not found. 150001, 150003 and 150009 mean the label exists but has no scans yet.
* **No credentials?** **Check top 10 on USPS.com** opens the official USPS tracking page in your
  browser, with the candidates comma-separated in `tLabels`, so you can look them up yourself.

## How recovery works

Every layer runs on every frame, and the evidence is combined:

1. **Image variants** for each frame: grayscale, CLAHE contrast, adaptive threshold,
   sharpening, upscaling, deskew and ±2–3° rotations. Every decoder runs on every variant.
2. **Full barcode decodes** with ML Kit Barcode Scanning (bundled model) and ZXing, on the
   located barcode, the area above the printed number, the whole frame, and overlapping crops.
   A decode whose check digit passes ends the search right away.
3. **Partial Code 128 decoder** (`core/.../barcode/Code128Partial.kt`):
   * Samples many scanlines.
   * Anchors the symbol grid on the start or stop pattern, so either end can be torn off.
   * Estimates the module width.
   * Scores each 11-module slot against all 107 patterns, using the measured bar/space widths
     and a template match. Damaged slots come out as flat distributions, not confident wrong
     ones.
   * Applies the GS1-128 layout (FNC1, `420`+ZIP, FNC1, tracking number), the mod-103 checksum
     and the USPS mod-10 check digit exactly, with forward–backward over the checksum states.
     This fills in destroyed symbols.
   * Reports which digit positions were recovered this way and how confident each one is.
4. **DataMatrix** retries on many crops and variants (Reed–Solomon survives heavy damage).
5. **OCR** of the printed number with ML Kit Text Recognition v2 (bundled). It keeps
   per-character confidence. A low-confidence character becomes a ranked list through a
   confusion model (0/6/8/9, 1/7, 3/8, 5/6, O→0, S→5…). Characters are placed into positions by
   their x-pitch and the 4-4-4-4-4-2 grouping, so a missing character becomes an unknown instead
   of shifting the rest.
6. **Multi-frame consensus:** per-position voting across frames and variants.
7. **Fusion:** per-position log-linear combination of OCR and barcode evidence into a
   probability for each digit 0–9. The barcode's exact joint likelihood is also applied
   (two digits share one symbol).
8. **Candidates:** an exact k-best search over (position, check-sum state). It returns the same
   numbers as enumerating every combination of the unknown positions and keeping the
   check-digit-valid ones, without listing 10ⁿ combinations. These are ranked by:
   * the digit probabilities
   * the USPS structure (channel 92–95, 9-digit Mailer IDs start with 9)
   * the label text (the service name narrows the service type code, and the ZIP narrows the
     `420` routing)
   * the learned history

Supported formats:

* IMpb 20, 22 and 26 digits, plus the `420`+ZIP routed 30 and 34 digit barcode forms. Numbers
  are parsed into channel, service type code, Mailer ID, serial and mod-10 check digit.
* S10 international numbers (`AA123456785US`, mod-11 check).

The service-name → service-code table in `ServiceHints.kt` is a soft hint: it only nudges the
ranking and never removes a candidate.

## On-device models

Three small models were trained from synthetic damaged labels, offline with no API keys. See
`training/README.md` for how. A benchmark then measured recovery with and without each one
(`models/BENCHMARK.md`):

* 96 rendered labels, physically damaged and photographed twice
* full pipeline: ZXing, the partial Code 128 decoder, and a simulated OCR that fails where the
  ink is damaged

A model is kept only if it fixes clearly more cases than it breaks.

| Model | Top-1 (96 labels) | Fixed | Broke | Shipped |
|---|---|---|---|---|
| none | 60 | | | |
| Damaged-digit classifier (CNN, 659 KiB) | **76** | 16 | 0 | **yes** |
| Restoration (denoise/deblur, 22 KiB) | 61 | 2 | 1 | no |
| Region detector (368 KiB) | 59 | 1 | 2 | no |

The digit classifier reads a 32×32 crop of each printed digit and gives probabilities for 0–9.
It also reads digits OCR missed, at their pitch-estimated position. Its probabilities join the
per-position fusion next to OCR and barcode evidence.

The restoration model and the region detector train fine:

* restoration: pixel error 0.098 → 0.036
* detector IoU: barcode 0.93, number 0.85

But neither measurably improved recovery: the existing preprocessing variants and the barcode
locator already cover those cases. So they are not bundled. They stay in `models/`, and the app
loads any model that a future benchmark run enables in `models/manifest.json`.

On the phone, models run with the LiteRT interpreter. The GPU delegate is used when the device
supports it, otherwise the CPU. Settings shows which models are in use.

`./gradlew :app:connectedDebugAndroidTest` repeats the with/without comparison on a device with
the real ML Kit OCR. It hasn't been run here, because this environment has no emulator.

## Privacy

All processing happens on the phone with bundled models. The app has no analytics and makes no
network calls, with one exception: the optional USPS verification. It is off until you add your
own credentials and turn it on, and it only sends candidate tracking numbers to apis.usps.com.
History and any saved digit crops stay on the phone unless you export them. Nothing is uploaded
automatically.

## Development

* `core/`: pure Kotlin/JVM library with all the digit logic, image processing, decoders, fusion
  and a synthetic damage generator. It is unit-tested on the JVM.
* `app/`: Android app (Kotlin, Jetpack Compose, CameraX, ML Kit bundled models, ZXing, Room,
  LiteRT). Min SDK 26, target SDK 37.
* `mltools/`: JVM-only tools. It renders synthetic labels for the training data and runs the
  with/without-model benchmark.
* `training/`: TensorFlow (CPU) training scripts and LiteRT export. `models/` holds the trained
  models and the benchmark results.
* Gradle with a version catalog (`gradle/libs.versions.toml`). It builds from a clean clone with
  JDK 17+ and the Android SDK (platform 37.2).

```sh
./gradlew :core:test :app:testDebugUnitTest   # unit tests + synthetic benchmark
./gradlew :app:assembleDebug                  # debug APK
./gradlew :app:connectedDebugAndroidTest      # on-device accuracy benchmark (needs a device/emulator)
```

Tests cover:

* check digits (mod-10, S10, Code 128)
* IMpb parsing, including GS1 payloads
* candidate enumeration, which is cross-checked against brute force
* the required manual case: `9234?9027507?500211709` gives exactly 10 valid candidates,
  including `9234190275070500211709` and `9234690275075500211709`
* ranking with history and label-text priors
* OCR alignment with missing or confused characters
* the partial Code 128 decoder with destroyed symbols, a torn start or stop, blur, noise and
  upside-down barcodes

`PipelineBenchmarkTest` renders damaged IMpb barcodes and simulates an imperfect OCR read, runs
the whole pipeline, and prints the recovery rate. CI puts that number in the job summary.

`LabelBenchmarkTest` (instrumented) renders full labels on the device (text and barcode),
damages them (blur, smudges, missing ink, tears, fading, rotation), runs the real ML Kit
pipeline and reports the recovery rate in logcat (tag `LabelBenchmark`). CI compiles it but
doesn't run it, because that needs an emulator or device.

[PLAN.md](PLAN.md) has the design notes.

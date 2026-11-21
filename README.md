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

**Updating instead of reinstalling:** once the `LABELRESCUE_KEYSTORE` signing secret (below) is
set, every build's APK is signed with the app's own key, so a new build installs over the old one
and keeps your history, settings and crops. Without the secrets, each build uses the build
machine's throwaway debug key, and a newer build won't install over an older one ("App not
installed") until you uninstall it. Switching to the app's own key needs that uninstall once.
Export your history (History > Export) and training crops first if you want to keep them.

GitHub keeps artifacts for 90 days by default.

### Signing (one secret)

Like the other apps in this repo, signing uses one repository secret, **`LABELRESCUE_KEYSTORE`**
(Settings > Secrets and variables > Actions > **Secrets** tab, Repository secrets): the keystore
file, base64-encoded. The keystore's password is the fixed `android` and its key is named
`labelrescue`; the file itself is what's kept secret. With it set, every build is signed with the
same key, so updates install over the old app, and the second job also builds a signed release
APK and AAB (artifact **LabelRescue-release**). If the secret doesn't open with that password,
the build says so and uses the throwaway debug key.

Make the keystore once, and keep a copy somewhere safe:

```sh
keytool -genkeypair -keystore labelrescue.keystore -storetype PKCS12 -alias labelrescue \
  -keyalg RSA -keysize 2048 -validity 36500 -storepass android -keypass android -dname "CN=Label Rescue"
base64 -w0 labelrescue.keystore   # paste the output into LABELRESCUE_KEYSTORE
```

Never commit the keystore. `*.keystore` and `*.jks` are in `.gitignore`.

## Using it

* **Scan label (burst):** line the label's 4 corners up with the corner marks (see Camera
  guide). The marks turn green, and **Burst capture** unlocks, once the label is lined up and in
  focus. Hints at the top tell you what to fix, for example "Move closer", "Move back",
  "Straighten the phone", "Hold steady, focusing…", or "More light needed". If a label's barcode is missing or destroyed, **Capture anyway** appears
  after 8 seconds.

  The burst grabs 12 frames from the camera stream plus 2 full-resolution stills. Turn on the
  torch for dim light.
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

## OCR, decoders and label context

* **Two OCR engines:**
  * ML Kit Text Recognition v2.
  * PaddleOCR PP-OCRv5 mobile (Apache-2.0), converted to LiteRT and bundled (16 MB). It runs on
    every crop of the number line.

  PaddleOCR gives a probability for every character, so a smudged 0 that it reads as 1 still
  carries "0: 20%". Both engines are separate evidence sources in the per-digit fusion.

  To regenerate the model: `python training/convert_ppocr.py` downloads the official ONNX model
  and converts and checks it.
* **Three barcode decoders:** ML Kit, ZXing, and zxing-cpp (Apache-2.0, the maintained C++
  ZXing), plus the partial Code 128 decoder.
* **Thermal-label damage:**
  * Dead printhead dots leave white hairlines through every bar. The partial decoder closes gaps
    narrower than a module.
  * Bars often print thinner or wider than spec. It uses edge-to-edge (bar+space) distances and
    tries thinner and wider bar templates.
  * Faded end bars can hide the start and stop patterns. It can then anchor the symbol grid on
    the outermost bars.
* **Number length:** prefers the common 22-digit IMpb over the legacy 20-digit form. It expects
  the channel digits 92–95 at the start, and allows for a line cut off at the edge.
* **Re-alignment:** each OCR read is re-aligned against the barcode's reading, so a dropped digit
  becomes an unknown instead of shifting the rest.
* **Service detection:** reads the mail class from the label with tolerance for damaged text
  ("SP' GROUN) ADVANTAGE"), or from the large service letter (G = Ground Advantage,
  P = Priority Mail, E = Priority Mail Express, F = First-Class). It boosts candidates with a
  matching service code.
* **Diagnostic log:** the results screen has **Copy log** and **Share log**. The log is a
  detailed, text-only record of the recovery for troubleshooting:
  * every OCR read, with per-character confidence, alternatives and positions
  * how each read aligned to digit positions
  * every barcode decode and partial-decoder measurement, and the layout candidates
  * the format vote and each source's probabilities per digit
  * the ranked candidates

  **Hide names and addresses** (on by default) masks capitalized label words. Nothing is sent
  anywhere unless you share it.
* **Training report:** one short entry per scan, and one photo per scan with names and addresses
  whited out. Every OCR'd text line is blanked unless it is only the tracking number or label
  wording (service, "USPS TRACKING #", "Mailed from", dates, weights). Barcodes and 2D codes stay.
  If OCR gives no line positions, no photo is kept. OCR can miss a faint line, so the card shows
  the photo before you send it, and **Include photos** can be turned off. **Share & clear**
  attaches the photos. **Copy & clear** copies the text and saves the photos to
  Pictures/Label Rescue (Android 10+). Either one clears the report.

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

## Camera guide

Four corner marks frame a 4×6 in. label. Line the label's corners up with them. The burst is
allowed once:

* the label's paper fills the marks (checked when it stands out from the background, such as a
  brown box; on a white box this check is skipped)
* a barcode inside the marks is wide enough for the right distance, straight, well lit and sharp

After 8 seconds, **Capture anyway** appears.

## UPS packages delivered by USPS

Some UPS services (Ground Saver and SurePost, service code YW in the 1Z number) hand the package
to USPS for the last mile. Their labels carry both a UPS "1Z" number and the USPS number.

* The app reads 1Z numbers offline from the UPS barcode, the MaxiCode (the round bullseye code)
  and the printed text, and checks UPS's check digit. One OCR lookalike swap (O/0, I/1, S/5, ...)
  is corrected only if exactly one swap makes the check digit valid.
* The results show the 1Z number it read, with its confidence and Copy. The app doesn't look
  anything up: it has no network access.
* **Find USPS tracking on UPS.com** appears when the UPS number is above 89% sure (a barcode or
  MaxiCode read 99%, printed text passing the check digit 93%, printed text that needed a
  lookalike correction 80%) and the USPS number is below 76% (a full decode is 100%, otherwise
  the top candidate's confidence; no USPS reading at all counts as 0%). It opens UPS's normal
  tracking page in your browser with the 1Z number filled in.
* There, long-press the USPS tracking number, tap **Copy**, and come back. The app finds the
  number on the clipboard (or tap **Paste USPS number**), checks its check digit, and compares it
  with the label: a different length, or digits the label shows clearly (90%+) as something
  else, are flagged in red as a probable wrong copy. **Use this number** sets it as the result
  (or opens it as a typed number when the label's own number wasn't readable).

## Privacy

Everything happens on the phone with bundled models. The app has no network access at all (the
INTERNET permission is removed, also the one libraries ask for), no accounts and no analytics.
History, training crops and reports stay on the phone unless you export or share them.

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

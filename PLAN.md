# Label Rescue — plan

Recover full USPS tracking numbers from damaged labels, fully offline. Accuracy first, speed second.

## Layout
- `core/` — pure Kotlin/JVM library (no Android). Everything that decides digits lives here so it can be
  unit-tested on the JVM: USPS formats and check digits, IMpb parsing, candidate enumeration and ranking,
  image preprocessing on a simple `GrayImage`, the partial Code 128 decoder, ZXing decoding, OCR evidence
  modelling (confusions, alignment), multi-frame voting and fusion, and the synthetic label generator.
- `app/` — Android (Kotlin, Compose, CameraX, ML Kit bundled models, Room, EncryptedSharedPreferences).
  Thin adapters turn camera frames / gallery images into `GrayImage`, call ML Kit, and feed results into
  `core`.

## Evidence model
Every source produces, per digit position of the tracking number, a probability vector over 0–9
(`DigitEvidence`). Unknown = uniform. Sources:
1. Full barcode decodes (ML Kit, ZXing, on every variant of every frame). A decode whose check digit
   passes ends the search with high confidence.
2. Partial Code 128: scanlines → run lengths → module width → per-symbol likelihoods over the 107
   patterns (edge widths + grid template matching). The GS1-128 structure (Start C, FNC1, "420"+ZIP,
   FNC1, tracking) and the mod-103 checksum constrain the symbols; damaged symbols are filled in by
   enumerating consistent symbol sequences. Reports recovered positions with confidence.
3. DataMatrix (ML Kit + ZXing over many crops and variants).
4. OCR (ML Kit text v2): per-character confidence; low confidence characters become a ranked list via
   a digit confusion model (0/6/8/9, 1/7, 3/8, 5/6, O→0, I→1, S→5, B→8 …). Characters are aligned to
   positions using the 4-4-4-4-4-2 grouping and bounding-box pitch so a missing character becomes an
   unknown instead of shifting the rest.
5. Multi-frame consensus: per-position votes across frames and variants.
6. Fusion: log-linear combination of sources (smoothed so one wrong source can't zero a digit).

## Candidates
- Positions with a dominant digit are fixed; others keep their ranked options.
- Up to 10^6 combinations are enumerated exactly; beyond that a k-best dynamic program over
  (position, weighted-sum mod 10) gives the exact top-K check-digit-valid numbers.
- Score = Σ log p(digit) + structure prior (channel 92–95, service code from label text, 9-digit MIDs
  start with 9) + learned prior (Mailer IDs and service codes from confirmed history).
- Confidence % = normalized posterior over the valid candidates.

## Stages (commit after each, tests green)
1. Number logic + manual entry + tests (check digits, parsing, enumeration, ranking).
2. Camera (burst 10+ frames, gallery import, crop) + preprocessing + OCR.
3. Barcode decoding (ML Kit + ZXing, DataMatrix) + fusion.
4. Partial Code 128 decoder + synthetic damaged-label tests.
5. UI polish (digit crops, tap-to-pick, copy/share/confirm), Room history with export/import,
   optional USPS API verification with encrypted credentials, dark mode.
6. CI workflow (debug APK + unit tests + artifact; signed release job that skips without secrets), README.

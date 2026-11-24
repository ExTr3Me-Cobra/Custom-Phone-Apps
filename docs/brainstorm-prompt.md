I'm building an offline Android app that recovers USPS tracking numbers from DAMAGED thermal shipping labels (4x6, photographed with a phone). I want serious, specific ideas to improve accuracy, beyond "collect more training data".

## The label

Each label has three copies of the same number:
- **IMpb:** a GS1-128 / Code 128 barcode. Its data is "420" + destination ZIP, then FNC1, then the tracking number. The tracking number is 20–34 digits and ends in a MOD 10 check digit. Code 128 also has its own mod-103 checksum.
- **The printed number:** the same digits in human-readable text below the barcode.
- **IMmb:** two GS1 DataMatrix codes with the same data.

Number structure (USPS Publication 199): channel 92 / 93 / 94 / 95, a 3-digit service type code (one of ~346 valid values), a mailer ID of 6 or 9 digits depending on the channel, a serial number, then the check digit.

## The damage, most common first

1. **Dead print-head elements.** These are thin white vertical lines, 1 to ~10 px in a phone photo, running the whole label top to bottom. They cut through bars and printed digits.
2. **Fading,** often only one half of the label.
3. Streaks.
4. Smudges.
5. Blur and glare from the phone.

## What the app does now (Kotlin, fully on-device)

The user takes a burst of ~5–16 photos. Then:

1. **Full decode:** ZXing on every frame.
2. **Quick read:** OCR-only. It accepts if at least 5 identical complete reads come from 2 or more frames, the check digit holds, and no dead line crosses a digit the digit model doesn't confirm.
3. **Otherwise, the full recovery:**
   - **Partial Code 128 decoder.** Scanlines are normalised; contrast is judged locally when one side is faded. Start/stop anchors set a symbol grid. Each 11-module slot is scored by template matching plus bar/space edge widths, giving per-symbol probabilities. Evidence is averaged across frames and image variants, then GS1 structure and both checksums are applied by forward-backward, giving per-digit marginals.
   - **OCR.** ML Kit on the whole frame; a PaddleOCR recognizer on the number line.
   - **Digit model.** A small CNN (32×32) classifies each printed digit crop. It is trained on synthetic digits, ~900 real crops, and real crops with artificial damage calibrated to real labels. It reads 86 of 92 real damaged digits.
   - **Fusion.** For each digit position, a product of per-source distributions with hand-set floors.
   - **Candidate ranking.** A beam search with structural priors (valid service type codes, mailer ID length per channel, ZIP from label text) plus an exact barcode likelihood. The user sees a ranked list with per-digit confidence and can tap to fix any digit.
4. **DataMatrix rescue.** Reed–Solomon with errors and erasures (dead columns and uncertain modules marked as erasures), with its own grid fit when ZXing's corners or size are off.

## Measured

- On real damaged labels, run as the phone does: 17 of 20 labels have the true number first.
- The true number's probability (geometric mean) is 59%.
- On 96 synthetic damaged labels: 83 first.

## Remaining failures

- **Faded labels.** OCR confidently reads faded 6/9/8 as "1" or "7", and many correlated reads agree. The barcode decoder often has the right digit as its top choice there, but only at 20–50%.
- **Streaked labels.**
- **One label with wide dead bands** (several modules wide) across both the barcode and the digits. It has no ruled lines nearby, so the dead columns can't be found from gaps in the ruled lines.
- **Dead lines in the barcode.** Treating dead columns as erasures in the decoder didn't help on real labels. Gap-closing (morphological closing of thin white gaps) already handles thin lines.

## Constraints

- Fully offline on a mid-range Android phone; a scan budget of ~30 s.
- No carrier APIs.
- User mode stores nothing, so no per-user history prior.
- Few real damaged examples: about 20 labels, plus ~900 digit crops.

## What I want from you

1. Your top 10 ideas, ranked by expected accuracy gain on these failure types. For each: the mechanism, why it helps here, the effort, and how I'd measure it.
2. "Big swing" ideas, for example:
   - analysis-by-synthesis: render each candidate number's barcode and digits, and score them against the photo;
   - joint decoding of all three copies under one likelihood;
   - calibrated fusion learned from few examples;
   - multi-frame super-resolution;
   - modelling correlated OCR errors.
3. Known techniques or papers from damaged 1D barcode decoding, Reed–Solomon soft-decision decoding (GMD, Chase, Koetter–Vardy), or OCR of degraded thermal print that apply here.
4. What you think would NOT help, and why.

Be concrete: algorithms, formulas, thresholds. No generic advice.

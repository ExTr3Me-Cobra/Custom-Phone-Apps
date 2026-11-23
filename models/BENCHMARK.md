# Model benchmark

Synthetic damaged labels (rendered, physically damaged, photographed twice), ZXing + partial Code 128 decoder + simulated OCR that fails where the ink is damaged. 96 cases, top-1 = best candidate is the true number.

| Configuration | Top-1 | Top-3 | Fixed | Broke | Kept |
|---|---|---|---|---|---|
| No models | 69/96 | 74/96 | | | |
| + digit_classifier | 78/96 | 81/96 | 9 | 0 | yes |
| + restore | 70/96 | 76/96 | 2 | 1 | no |
| + region_detector | 71/96 | 74/96 | 3 | 1 | no |
| Kept models together | 78/96 | 81/96 | | | |

Rule: keep if (cases fixed - cases broken) >= 3 (3% of cases, at least 2) and neither top-1 nor top-3 is lower than without it.

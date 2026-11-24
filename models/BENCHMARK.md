# Model benchmark

Synthetic damaged labels (rendered, physically damaged, photographed twice), ZXing + partial Code 128 decoder + simulated OCR that fails where the ink is damaged. 96 cases, top-1 = best candidate is the true number.

| Configuration | Top-1 | Top-3 | Fixed | Broke | Kept |
|---|---|---|---|---|---|
| No models | 73/96 | 77/96 | | | |
| + digit_classifier | 83/96 | 84/96 | 10 | 0 | yes |
| + restore | 72/96 | 76/96 | 0 | 1 | no |
| + region_detector | 70/96 | 73/96 | 0 | 3 | no |
| Kept models together | 83/96 | 84/96 | | | |

Rule: keep if (cases fixed - cases broken) >= 3 (3% of cases, at least 2) and neither top-1 nor top-3 is lower than without it.

# Model benchmark

Synthetic damaged labels (rendered, physically damaged, photographed twice), ZXing + partial Code 128 decoder + simulated OCR that fails where the ink is damaged. 96 cases, top-1 = best candidate is the true number.

| Configuration | Top-1 | Top-3 | Fixed | Broke | Kept |
|---|---|---|---|---|---|
| No models | 65/96 | 69/96 | | | |
| + digit_classifier | 78/96 | 80/96 | 13 | 0 | yes |
| + restore | 64/96 | 68/96 | 0 | 1 | no |
| + region_detector | 65/96 | 68/96 | 2 | 2 | no |
| Kept models together | 78/96 | 80/96 | | | |

Rule: keep if (cases fixed - cases broken) >= 3 (3% of cases, at least 2) and neither top-1 nor top-3 is lower than without it.

# Strength standards

`build_standards.js` writes `app/src/main/assets/standards.json`, the levels every rank is measured against, and
`build_report.txt` (hold-out test, corrections, coverage). Everything it reads is in `inputs/`.

```
node tools/standards/build_standards.js
FAVOR=gemini node tools/standards/build_standards.js   # developer variant: standards_gemini.json
```

The developer setting "Favor Gemini's data" ranks with `standards_gemini.json`: every weighted exercise Gemini covers takes
its middle level 80% from Gemini's own number and 20% from the normal build, with the same stars and spread.

Levels: Beginner / Novice / Intermediate / Advanced / Elite = stronger than 5 / 20 / 50 / 80 / 95% of people who do
resistance training, at the lifter's sex and body weight (rows every 10 lb: men 110-310, women 90-260). 1RM in lb:
barbells total weight, dumbbells and kettlebells per hand, cables on a 1:1 pulley. Bodyweight moves: max reps, counting
people who can do at least one.

## How each exercise is built (the app's stars)

| Star | What it means | How |
|---|---|---|
| Gold | A 1RM ratio measured in that sex in a published study | `inputs/research_lifts.json` (made by `research_lifts.py`): the gym-goer bench press level and ratios measured in the same people, shaped across body weight by the OpenPowerlifting / OpenWeightlifting median curves |
| Blue | Estimate | Research lifts that rest on an assumption or one low-confidence study; exercises estimated as a ratio to a research anchor (bench, squat, deadlift, standing press, lat pulldown) from the four AI-generated databases, each source's overall bias removed against the research lifts, where at least two sources agree within 25%; bodyweight moves from a strength model (anchor strength vs share of body weight lifted, reps from a fit to the Nuzzo et al. 2024 reps-to-load data) |
| Purple | Rough estimate | AI-generated databases only: one source, sources more than 25% apart, or a move the strength model can't cover (sit-ups, crunches, leg raises). Ranked only in "All on + AI" mode. Men and women can get different stars for the same exercise |

Spread between levels: log-normal, sigma 0.26 men / 0.29 women (Isenmann et al. 2026's elite-to-intermediate gap), scaled per
equipment family by the AI sources' relative spreads. Age: `Standards.kt` scales every level by the OpenPowerlifting age
curve in `inputs/openpowerlifting_curves.json`.

## Inputs and their sources

| File | Contents | Source and licence |
|---|---|---|
| `research_lifts.json` | 31 research lifts | Isenmann E. et al. (2026). Normalization of muscle strength measures in barbell exercises by sex, age, and anthropometry. *Scientific Reports* (CC BY 4.0). Gym-goer bench press and ratios between lifts from the studies cited per lift in `research_lifts.py` (Tiggemann 2011; Rodríguez-Ridao 2020; Seo 2012; Nieto-Acevedo 2023; Larsen 2021; Massini 2022; Ribeiro 2024; Swinton 2011; Camara 2016; Saeterbakken 2011/2013; Jackson 2012; Cotterman 2005; Szyszka & Czaplicki 2021; Czaplicki 2019; Piper 2021/2022, 10-rep maxes). Only measured numbers (facts) are used, with citation |
| `openpowerlifting_curves.json` | Median 1RM vs body weight per sex and lift; strength vs age | OpenPowerlifting bulk data, raw division (public domain), https://openpowerlifting.gitlab.io/opl-csv/ ; OpenWeightlifting event data (public domain), https://github.com/euanwm/OpenWeightlifting . Made by `derive_openpowerlifting.py` |
| (in `build_standards.js`) | Reps to failure at % of 1RM: two fitted constants, not the data | Nuzzo J.L. et al. (2024). Maximal number of repetitions at percentages of the one repetition maximum. *Sports Medicine* (CC BY 4.0), data on OSF |
| `grip_tomkinson_2025.json` | Hand-grip percentiles, ages 20-39 | Tomkinson G.R. et al. (iGRIPS, 2025). International norms for adult handgrip strength. *Journal of Sport and Health Science* 14:101014 (CC BY 4.0). Values from the paper's percentile table |
| `ai_databases.json` | Four AI-generated strength databases, parsed | Generated for this app with Claude, Gemini, ChatGPT and DeepSeek |
| `ai_conventions.json` | Where a database measured an exercise a different way (per stack vs both stacks, one arm vs both, one dumbbell in both hands vs one per hand) | Each entry names the source's own related exercise that shows it; converted or left out |
| `ai_corrections.json` | Suspected unit mix-ups and outliers in those databases | Each one is re-checked by the build against the other sources and the research only; the build report lists which were kept |

Every number in the standards comes from the inputs above.

## V2 (trial, developer setting "V2 rankings")

`V2=1 node tools/standards/build_standards.js` writes `standards_v2.json` and `build_report_v2.txt`; V1 is unchanged.

| Fix | What V2 does | Source |
|---|---|---|
| Light lifters too easy | Below the reference weight, levels never fall faster than body weight (log-log slope ≤ 1). Also stops light men's rep standards dropping below heavier men's | Jaric S. (2002). Muscle strength testing: use of normalisation for body size. *Sports Med* 32:615-631 (strength ∝ mass^~0.67) |
| Women's pull-ups / push-ups too close to men's | Women's medians set to 0.41 × men's (pull-ups) and 0.55 × men's (push-ups); other moves with the same anchor take the same factor | Lockie R.G., Orr R.M., Dawes J.J. (2022). *IJERPH* 19:13918 (CC BY); Knapik J.J. et al. (2011). *J Occup Med Toxicol* 6:26 (CC BY) |
| Women's leg press too high | Men's leg press / squat ratio × women's squat | Piper et al. 2021/2022 (men), Isenmann et al. 2026 (CC BY) |
| Women's lat pulldown too high | Men's ratio × the women/men seated-row difference measured in the same people | Seo D.I. et al. (2012). *J Sports Sci Med* (open access) |
| Two-kettlebell front squat | Gemini, ChatGPT and DeepSeek gave both bells together; halved to per bell | Their own goblet squat numbers (one bell) are lower |
| Tate press | Per dumbbell (it uses two) | The exercise's directions |
| Variants | Full squat = back squat; powerlifting bench = bench (assumed); dumbbell shoulder press and seated dumbbell press (same seated movement) share the geometric mean | Assumptions, labelled in each note |
| High-rep sets on machines and isolation lifts | Sets over 12 reps count as 12 for the ranked estimated max (`rc` in the file) | Reynolds J.M., Gordon T.J., Robergs R.A. (2006). *J Strength Cond Res* 20:584-592; Nuzzo J.L. et al. (2024). *Sports Med* (CC BY) |
| Owner's tests | `inputs/owner_tests.json`: the owner's tested ratios (no body weight or loads) count as one vote next to the AI databases, men only, never on research lifts | The owner's Research mode tests, Oct 2026 |

Checked and left unchanged:
- **Spread between levels.** Isenmann et al. 2026 (Supplement S16-S17), from beginners to elite, measured an SD of ln 1RM of
  0.19-0.23 (men) and 0.26-0.28 (women) at a given body weight, so the current 0.26 / 0.29 is already at least as wide.
- **Hang clean above power clean.** This matches Szyszka & Czaplicki 2021 (hang clean 0.96, power clean 0.89 × clean,
  caught in a full squat), cited from its abstract only.

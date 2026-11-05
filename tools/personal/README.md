# Personal-use standards (removable)

The owner's own choice: a separate set of standards that may use sources that are **not** free for commercial use
(for example Strength Level). It is kept entirely apart from V1, V2 and the Gemini variant. Nothing here feeds into
them, and they never read anything here.

In the app: Developer settings → "Personal-use rankings". When it's on, only these standards rank you, and exercises
they don't cover aren't ranked.

## The data
`inputs/standards_filled.csv`: all 739 exercises, filled in by another AI for the owner, mostly from Strength Level's
tables (its terms forbid copying its standards; the owner's choice, for personal use in a private repo). 551 exercises
are ranked (timed holds on seconds); blank rows aren't. `build_personal.py` applies a short list of common-sense corrections (listed in it, each copied or scaled from another row
of the same file, e.g. weighted-vest calf raises = the barbell calf raise) and turns it into the app's file (see its header for
the body-weight scaling and stars).

## Purged rankings
Developer settings → "Purged rankings": the app's standards from before the October 2026 purge, for the 315 exercises
still in the app. `inputs/purged_tables.json` was recovered from the owner's comparison page; `build_purged.py` writes
`app/src/main/assets/personal/standards_purged.json`.

## Files (everything to delete to remove it)
- `tools/personal/` (this folder: build scripts and fetched inputs)
- `app/src/main/assets/personal/standards_personal.json` and `standards_purged.json`
- Code marked `PERSONAL-USE (removable)`: `StandardsData.kt` (the `personal` list and its line in `all`),
  `Model.kt` (`devPersonal`, json key `dper`) and `Profile.kt` (the toggle)

Run `git grep -n "PERSONAL-USE\|devPersonal"` to find every spot (it also hides every source in the app while it's on). A full purge from history also needs the same `git filter-branch`
pass used for the earlier clean-up.

## Format
The same as `standards.json`: per exercise `i` (exercise id), `lift`, `ln`, `m` / `f` rows of
`[body weight lb, Beginner, Novice, Intermediate, Advanced, Elite]` (better than 5 / 20 / 50 / 80 / 95% of lifters),
`r` (reps), `d` (per dumbbell), `k`, `ai`, `a`, `note` (the source).

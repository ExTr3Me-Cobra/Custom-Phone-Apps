# Personal-use standards (removable)

The owner's own choice: a separate set of standards that may use sources that are **not** free for commercial use
(for example Strength Level). It is kept entirely apart from V1, V2 and the Gemini variant. Nothing here feeds into
them, and they never read anything here.

In the app: Developer settings → "Personal-use rankings". When it's on, only these standards rank you, and exercises
they don't cover aren't ranked.

## The data
`inputs/standards_filled.csv`: all 739 exercises, filled in by another AI for the owner, mostly from Strength Level's
tables (its terms forbid copying its standards; the owner's choice, for personal use in a private repo). 552 exercises
are ranked (timed holds on seconds); blank rows aren't. `build_personal.py` turns it into the app's file (see its header for
the body-weight scaling and stars).

## Files (everything to delete to remove it)
- `tools/personal/` (this folder: build scripts and fetched inputs)
- `app/src/main/assets/personal/standards_personal.json`
- Code marked `PERSONAL-USE (removable)`: `StandardsData.kt` (the `personal` list and its line in `all`),
  `Model.kt` (`devPersonal`, json key `dper`) and `Profile.kt` (the toggle)

Run `git grep -n "PERSONAL-USE"` to find every spot. A full purge from history also needs the same `git filter-branch`
pass used for the earlier clean-up.

## Format
The same as `standards.json`: per exercise `i` (exercise id), `lift`, `ln`, `m` / `f` rows of
`[body weight lb, Beginner, Novice, Intermediate, Advanced, Elite]` (better than 5 / 20 / 50 / 80 / 95% of lifters),
`r` (reps), `d` (per dumbbell), `k`, `ai`, `a`, `note` (the source).

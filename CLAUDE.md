# Working notes for Claude

Loadout (package `com.ironlog.app`): Kotlin + Jetpack Compose. No local SDK; CI builds every push to
`claude/workout-log-app` and publishes release `loadout-N` with `Loadout.apk`.

## Standing rules
- After every push, give the direct APK link
  `https://github.com/ExTr3Me-Cobra/Phone-App-Test/releases/download/loadout-N/Loadout.apk` and check it returns 302.
- No AI model IDs anywhere in the repo. No pull requests unless asked.
- Never compare our data with Strength Level, for any reason, not even as a reference point or sanity check.
- Nothing copyrighted, trademarked or not free for commercial use (Strength Level, ExRx, StrengthLog,
  Bodybuilding.com text, "Olympian" and similar). Cite studies by reference only; never copy their text or figures.
- Personal data stays out of the repo: Hevy exports, workout history, body weight or body fat, the OpenAI key, email.
- Version is `0.x.build`; bump the minor version for notable features. Ask the owner before going to 1.0.

## Waiting on the owner
- **Standards V2** (developer setting "V2 rankings", `V2=1` build, README section "V2") holds the data fixes. The owner
  decides whether it replaces V1. Left as is on purpose: the spread (Isenmann data says it's already wide enough) and
  hang clean above power clean (matches the study).
- The 72 medium-confidence exercise removal candidates.
- A link for the tip button.
- Weighted-vest calf raises (two- and one-leg) need new standards.
- The "Clean Search Data" doc is out of date (old 1.00× bench, old spread).

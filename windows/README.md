# Mammy Frame for Windows

The same photo frame as the Android app, for a Windows PC or tablet (Windows 10 or 11, 64-bit).

## Install
Open the latest **mammy-frame-windows-N** release on the Releases page.
- **MammyFrame-Windows-Setup.msi**: download and open it to install. No administrator needed.
  If Windows says "Windows protected your PC", click **More info**, then **Run anyway**.
- **MammyFrame-Windows.zip**: no install. Unzip it anywhere and open **Mammy Frame.exe**.

## What's the same as the tablet app
- Home screen: the name in script and a Play button. Click the name 10 times quickly for the
  developer settings: Photos folder, Schedule, Transitions and Ai Rotate, plus
  **Turn off developer settings**.
- Slideshow: always shuffled, full screen, each photo the right way up. Click (or tap) for the
  big back / pause / forward buttons and the small **End Slideshow** in the top left (they hide by themselves after 2 seconds). Keyboard: ← →, space, Esc.
- Schedule: starts the slideshow at the start time and goes black at the end time. Clicking the
  black screen brings up the menu, and it goes black again after a minute.
- Ai Rotate: the same AI, rules, review screen, timer and switches. JPEGs are turned by changing
  only their rotation tag, so there's no quality loss and their "date modified" is kept. PNG and
  BMP are saved turned, with the original kept for undo. WebP and GIF are turned inside Mammy
  Frame only.
  Review keyboard: R = turn 90°, Enter or → = Next, Backspace or ← = Back, Esc = leave.

## Windows-only bits
- **Always open:** there's no close or minimise. Mammy Frame covers the whole screen (taskbar too),
  stays in front of other programs, and Alt+F4, Win+D and minimising don't get rid of it. If it's
  ended anyway (Task Manager, a crash), a small guard program opens it again within a few seconds
  (the guard is restarted too if it's ended). Only one copy opens at a time.
- **Force close Mammy Frame** (developer settings, tap it twice) is the only way to close it. Use it
  before installing an update. Windows shutting down or signing out closes it normally.
- **Open log folder** lets File Explorer come in front for 2 minutes.
- The slim bar at the top of the menus has **Move to next screen** when there's more than one
  screen. It opens on the screen it was on last time.
- Mammy Frame has to be open for the schedule to work (minimised is fine). At the start time it
  comes to the front by itself. **Start on power-up** (developer settings) opens it when Windows
  starts and signs in, straight into the slideshow (or the black screen if the schedule says it's night).
- While it's showing photos or the black screen, the PC won't sleep or turn the screen off.
  While Ai Rotate is checking, the PC won't sleep.
- Settings and Ai Rotate's list are kept in `%APPDATA%\MammyFrame`.
- **Copy diagnostic log** (developer settings) copies the last two runs from the log; **Open log folder**
  shows the whole file (`%APPDATA%\MammyFrame\log.txt`). The log records the PC and every screen,
  everything printed by the app and its libraries, every freeze or stutter over 100 ms with what
  the app was doing, frame rate every 10 s and slow frames, every click / key / window event and
  screen change, memory every 30 s, errors, and Java crash reports from earlier runs.

## Automatic Windows test
Every Windows build is tested on a real Windows machine before it's published: it's installed from
the installer, then opened with `--autotest <results folder> <photos folder>`, which goes through
every screen, makes a real mouse click, runs Ai Rotate on `windows/testdata` (a third of the
photos saved sideways on purpose), measures drawing speed and takes a screenshot of each step.
The download is only published if that passes. Screenshots and logs go to a separate
**mammy-frame-windows-test-N** pre-release either way.

## Build
`.github/workflows/build-mammy-frame-windows.yml` builds it on a Windows machine whenever
`windows/` (or the AI model or font it shares with the Android app) changes. To build locally:
`cd windows && gradlew.bat packageMsi`. Self-checks: `./gradlew selfTest -PtestArgs="ai <folder>"`
(AI accuracy), `"exif <photo.jpg>"` (lossless turning), `"e2e <folder> <out>"` (everything).

# Mammy Frame

A very simple digital photo frame for an Android tablet. There's also a Windows version: see
[windows/README.md](windows/README.md) (its own download, **mammy-frame-windows-N** on the Releases page).

## Home screen
- **Mammy Frame** name in a script font, with a big **Play** button.
- Normally that's all that shows. Every setting is in the hidden developer settings below.
- **Photos folder** (developer settings): tap to pick the folder the photos come from (its subfolders are included).
  It shows how many photos it found.
- **Developer settings** (hidden): tap the **Mammy Frame** name 10 times quickly to show the
  Photos folder, Schedule and Transitions settings. Tap **Turn off developer settings** to hide them
  again. Hiding them doesn't change them; the schedule keeps running.
- **Schedule** (developer settings): turn it on and pick a start time and an end time.
  - At the start time the slideshow starts by itself.
  - At the end time the screen goes solid black (and as dim as it goes) until the next start time.
  - Tapping the black screen brings up the home screen. If nobody starts a slideshow, it goes
    black again after 1 minute without being touched.
  - Works overnight too (for example 8 PM to 2 AM).
- **Transitions** (developer settings): Fade, Fade through black, Slide, Slide up, Zoom in, None, or a Mix of all;
  how long the transition takes; how long each photo shows (5 seconds to 10 minutes); and an
  optional gentle slow zoom on each photo.

## AI rotate (developer settings)
Finds sideways and upside-down photos in the chosen folder and turns them the right way up.

- **Check all photos** looks at every photo. First it reads the camera's own rotation tag. Then
  an AI looks at the picture itself. If the camera and the AI agree the photo is fine, it's left
  alone. If they disagree, it always goes to review.
- Photos the AI is sure about are turned and saved straight away. The rest go to **For review**.
- **Start review** shows the review photos one at a time, full screen, most sure first. Drag
  round in a circle to spin a photo; it snaps to the nearest quarter turn when you let go. The
  see-through **Next** button saves the photo as shown (the AI's turn or yours) and moves on;
  **Back** goes to the photo before, the way you saved it. The tiny ✕ in the top left leaves
  review. Each photo is decided one at a time; there's no "all at once".
- **Show them turned the AI's way**: on, review photos start the way the AI suggests; off, they
  start the way they are now (original).
- **Turned photos** (tap Show): turn any photo with the arrows. Each turn is saved straight
  away, and the photo stays in the list.
- **The slider** sets how sure the AI must be to turn a photo by itself (50–99%, default 95%).
  Moving it immediately moves photos between "turned" and "for review".
- **Ask before turning anything**: nothing is turned by itself. Photos the AI is sure are already
  right are left alone, and every turn it wants waits at the front of review (most sure first)
  for your OK. Switching it on puts back any automatic turns until you approve them.
- **Keep AI rotations**: switch it off to put every photo back exactly as it was. Switch it on
  again to turn them again.
- **Check new photos** later only looks at photos that are new or changed.
- It keeps going in the background with a progress notification.
- While it runs, the screen shows how long it has been going, about how long is left and the
  time it should finish (worked out from the average time per photo so far, so it settles as it
  goes). When it ends, the total time stays on screen (and is remembered); tap it to copy it.

How photos are saved:
- **JPEG** (almost all camera photos): only the rotation tag inside the file is changed. The
  picture itself is never re-saved, so there's no quality loss, and undoing is exact.
- **PNG / WebP**: saved turned with no quality loss. An exact copy of the original is kept inside
  the app until it's undone.
- **HEIC and other kinds** that Android can't save: turned inside Mammy Frame only.

Built for big folders (tens of thousands of photos):
- Each photo's result is saved on its own in a small database, so nothing is rewritten over
  and over, and stopping halfway loses nothing. "Check new photos" carries on where it stopped.
- It keeps the tablet working while checking, even if the screen goes off. On Android 14 and
  newer it isn't subject to Android's daily time limit for background work.
- The list of photos is saved, so the home screen and slideshow start straight away instead of
  looking through the whole folder each time. The slideshow quietly looks again if the saved
  list is over an hour old, so new photos show up next time.
- Thumbnails are limited to about 32 MB of memory, and the screens update at most once a second
  while checking.

Saving needs permission to change the folder. If the folder was chosen before this version,
choose it again (Photos folder card) and Mammy Frame will ask.

The AI is [deep-image-orientation-detection](https://huggingface.co/DuarteBarbosa/deep-image-orientation-detection)
(EfficientNetV2-S, 98.8% on its own test photos, MIT license, see `MODEL-LICENSE.txt`). It runs on
the tablet itself, so photos never leave it. Each photo is checked four times (turned each way)
and the answers are combined. Photos with no clear "up" (things shot from directly above,
patterns) are the hardest. Those usually end up in review.

## Screen sizes
The home screen fits itself to the screen it's on: settings in a row on tablets, stacked on phones
held upright, and side by side (name and Play on the left, settings on the right) on phones turned
sideways. The name, Play button and slideshow buttons scale with the screen, and everything
scrolls if it ever doesn't fit (for example with very large system text).

## Slideshow
- Always shuffled (every photo shows once before any repeat), always full screen. Each photo is
  shown whole and the right way up, portrait or landscape, with black around it.
- Tap the screen for large see-through **back**, **pause/play** and **forward** buttons. A very
  small **✕** in the top left corner ends the slideshow and goes back to the home screen.
- Tap anywhere that isn't a button to hide the buttons again.

## Install
1. Open the latest **mammy-frame-N** release on the Releases page and tap **MammyFrame.apk**.
2. Open it and allow installing from your browser if asked.
3. Open Mammy Frame and tap the name 10 times quickly. Tap **Photos folder** and choose the folder,
   set the **Schedule**, then tap **Turn off developer settings**.
4. In the Schedule box, tap **Allow** on the two notes if they appear ("Display over other apps"
   and "Alarms & reminders"). These let the frame switch on by itself at the start time even if
   the app was closed or the tablet restarted.

Tip: leave the tablet plugged in with Mammy Frame open. The app keeps the screen on while it's
showing, including the black screen, so a tap always works.

## Build
GitHub Actions (`.github/workflows/build-mammy-frame.yml`) builds a signed APK on every push and
publishes it as a release. Signing uses the `MAMMYFRAME_KEYSTORE` repository secret if set
(base64 keystore, alias `mammyframe`, password `android`), otherwise a key kept in the build cache.

The Great Vibes font is under the SIL Open Font License (see `FONT-LICENSE-GreatVibes.txt`).

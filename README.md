# Mammy Frame

A very simple digital photo frame for an Android tablet.

## Home screen
- **Mammy Frame** name in a script font, with a big **Play** button.
- **Photos folder**: tap to pick the folder the photos come from (its subfolders are included).
  It shows how many photos it found.
- **Schedule**: turn it on and pick a start time and an end time.
  - At the start time the slideshow starts by itself.
  - At the end time the screen goes solid black (and as dim as it goes) until the next start time.
  - Tapping the black screen brings up the home screen. If nobody starts a slideshow, it goes
    black again after 1 minute without being touched.
  - Works overnight too (for example 8 PM to 2 AM).
- **Transitions**: Fade, Fade through black, Slide, Slide up, Zoom in, None, or a Mix of all;
  how long the transition takes; how long each photo shows (5 seconds to 10 minutes); and an
  optional gentle slow zoom on each photo.

## Slideshow
- Always shuffled (every photo shows once before any repeat), always full screen. Each photo is
  shown whole and the right way up, portrait or landscape, with black around it.
- Tap the screen for large see-through **back**, **pause/play** and **forward** buttons. A very
  small **✕** in the top left corner ends the slideshow and goes back to the home screen.
- Tap anywhere that isn't a button to hide the buttons again.

## Install
1. Open the latest **mammy-frame-N** release on the Releases page and tap **MammyFrame.apk**.
2. Open it and allow installing from your browser if asked.
3. Open Mammy Frame, tap **Photos folder** and choose the folder, then set the **Schedule**.
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

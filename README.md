# Screen Tint

An always-on colour layer for Android that lets you set how much **red, green and blue** reach the
screen, going further than the phone's built-in colour settings allow.

* Red / Green / Blue sliders, fine 85–100% range by default (Wide range for 0–100%), changes show instantly.
* Warmth (about 8,000 K to 4,500 K), Tint (greener / pinker) and Brightness (down to 20%) adjustments.
* Gentle Strength slider (its middle is 25%) that fades the whole look in or out.
* **Calibrate with test images**: grey ramp, grey patches, colour bars, skin and nature tones and a
  full white screen to compare with paper, with the Warmth / Tint / Brightness sliders beside them.
* Presets (Warm, Very warm, Night red, Cool, Dim, Very dim) plus your own saved looks.
* Runs all the time and starts again by itself after every restart.
* Quick settings tile to switch it on and off.

## Install on your phone

1. On your phone, open this repository on GitHub, tap **Releases** and open the newest
   **Screen Tint** build.
2. Tap **ScreenTint.apk** to download it, then open the file and install it (same "unknown apps" /
   Auto Blocker steps as any APK from outside the Play Store).
3. Open **Screen Tint** and follow the one setup step: switch it on in Accessibility settings. If the
   switch is greyed out ("Restricted setting"), use App info > ⋮ menu > **Allow restricted
   settings** first.

## Good to know

* It's a see-through colour layer, so it can make colours weaker or the screen dimmer, but never
  brighter. The stronger the tint, the more the darkest parts of the picture take on the tint
  colour.
* It never covers the screen completely, so the phone always stays usable.
* The tint shows up in screenshots.
* Updating: uninstall the old version first, then install the new APK.

## How it's built

GitHub Actions (`.github/workflows/build-screen-tint.yml`) builds the APK on every push and attaches
it to a new release. Code is in `app/src/main/java/com/screentint/app/`.

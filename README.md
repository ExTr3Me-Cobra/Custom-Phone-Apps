# Light Check

An Android app that checks every night (10:00 PM by default) whether the living-room smart
lights are still on the home Wi-Fi. If they were turned off at the wall switch (no power, so
they drop off the Wi-Fi), it rings an alarm. If the phone isn't on the home Wi-Fi at that time,
it sends a "couldn't check" notification instead.

No brand-specific setup and no Google Home: the app looks for the lights on the Wi-Fi itself.

## Setup

1. Install **LightCheck.apk** from the newest "Light Check" release.
2. **Status → Setup**: tap **Fix** on each item (notifications, exact time, full-screen alarm,
   location "Allow all the time"; Android only tells apps the Wi-Fi's name with location access).
3. **Find lights**: with the switch on, scan; turn the switch off, wait, scan again. What
   disappeared is your lights. Save them.
4. **Settings**: check time and days, home Wi-Fi (**Use the Wi-Fi I'm on now**), alarm sound,
   volume, vibration, ring until dismissed, ring in silent mode, snooze. **Test alarm** and
   **Check now** try it out.
5. **Diagnostics**: Wi-Fi details, the saved lights live, every device on the Wi-Fi, and a log.

Lights that connect through a hub (e.g. a Philips Hue Bridge) or only by Bluetooth can't be found
this way.

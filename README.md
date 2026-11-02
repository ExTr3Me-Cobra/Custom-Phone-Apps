# Motion Dots

A personal Android app that shows motion-sickness cue dots, like Apple's Vehicle Motion Cues
(iOS 18) and Google's Motion Assist (Android 17). Dots at the edges of the screen move with
the vehicle, as if they were part of the scenery outside. Your eyes then see the motion your
inner ear feels, which eases the conflict that causes car sickness while you read or scroll.

For passengers facing forward. Never use it while driving.

## How the dots move

- **Speeding up:** the dots slide **down**.
- **Braking:** they slide **up**.
- **Left turn:** they slide **right**.
- **Right turn:** they slide **left**.
- **Steady speed or stopped:** they settle back to where they started.
- **Big motion:** once a set of dots has moved a set distance (*Travel before new dots appear*),
  it fades out and a fresh set fades in at the edge. The dots never slide into the middle of the
  screen or off it.

The app reads the phone's fused motion sensors (linear acceleration with gravity removed, plus
gravity). It works out the flat (horizontal) plane and which way you're facing, so the
direction is right however you hold the phone: upright, tilted, flat or in landscape.

Slow drift, sensor bias and hills are filtered out. The dots follow on a soft spring.

## Setup

1. Install **MotionDots.apk** from the newest "Motion Dots" release.
2. Open the app and tap **Fix** next to *Display over other apps*.
3. Tap **Turn dots on**. Add the **Motion dots** tile in Quick Settings, or the 1×1 widget, to
   switch them from anywhere. The notification has **Turn off**.
4. Optional: **Also over the status bar, notifications and Settings**. A normal overlay can't
   cover those screens (Android blocks it, which is also why Google's version doesn't show
   there yet), and Android caps its opacity at 80%. Turning this on lets the dots draw through
   an accessibility service, which reads nothing on the screen.

## On and off

Tap the Motion Dots app icon to turn the dots on or off (long-press it for Settings), or use the
Quick Settings tile. There is no automatic on or off.

## Widget

Long-press the home screen, then **Widgets → Motion Dots**, and add the 1×1 button. Tap it to
turn the dots on or off. The icon has no background and "Motion Dots" under it; it's white while
the dots are on and faded grey when they're off.

## Settings

- **Look:** colour (each has an outline), dot size, gap between dots, distance from the edge,
  opacity, and left and right edges or all four.
- **Motion:** sensitivity, smoothing, **Hide when not moving** (fades out after a time you set, 3–120 s, default 20 s),
  and **Facing backwards** (flips the direction).
- **Live motion:** the forward and sideways acceleration the app sees, for checking it.

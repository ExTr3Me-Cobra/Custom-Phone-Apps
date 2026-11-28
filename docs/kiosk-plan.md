# Kiosk setup: parked ideas (revisit once the phone, plan and printer are in hand)

## Hardware
- A spare Android phone in a lockable enclosure or security stand, with the charging cable
  routed inside it (search "lockable phone kiosk enclosure"; Compulocks and CTA Digital make
  them). Turn on the charge limit (Samsung: Protect battery, about 85%). Constant 100% charge
  can swell the battery.
- A cheap Bluetooth 4x6 thermal label printer. ZPL support makes printing from the app easiest.
  Bluetooth connects one phone at a time, which is fine for a single kiosk phone.

## Data in Barre, VT 05641 (no Wi-Fi in the building)
- Usage is tiny: a USPS lookup is a few MB, so 100 labels a month is well under 1 GB.
- Cheapest plans found (Nov 2026; check the carriers' own sites): Red Pocket on Verizon at
  about $5-8/mo, and Tello on T-Mobile at about $5-10/mo. In Barre, AT&T and Verizon cover
  best and T-Mobile is weaker. Check signal inside the building first. The phone must be
  unlocked.
- No-data alternative: a QR-code handoff. The kiosk shows the candidates as a QR code, anyone's
  phone opens USPS.com with all of them, and the person taps the right one on the kiosk.

## Locking the data to USPS.com only
- NetGuard (free, no root): block mobile data for every app except Label Rescue (and Chrome,
  if lookups stay in Chrome Custom Tabs).
- NextDNS (free) in allowlist mode, set as the phone's Private DNS: only USPS domains resolve.
  USPS.com loads a few helper domains, and the exact list has to be tested on the phone.
- Screen pinning was rejected: it's easy to escape, and Android explains how every time.

## USPS.com inside the app
- USPS terms of use (about.usps.com/who/legal/terms-of-use.htm) say nothing about apps,
  in-app browsers, framing or bots. The relevant lines are written permission to "reproduce,
  republish, upload" or "publicly display" material, and viewing "for personal,
  non-commercial home use".
- Chrome Custom Tab (what the app uses now): real Chrome, no embedding question, but no
  overlays possible.
- Built-in WebView, as an option to test in Developer Mode first:
  - Overlays drawn on top of the page (arrows, highlights, a tip banner) without editing
    USPS's page.
  - Optional detection of which candidate has tracking history ("This one looks right. Use it?").
  - Risks: breaks when USPS redesigns (should fail safe: no arrow), and USPS bot protection
    may challenge WebViews more than Chrome.

# Phityo

A tiny, account-free Android replacement for the stock **Fityo** app that ships with the **Trailviber** walking treadmill (BLE name `FS-A1139C`).

Designed for one use case: open the app, see live stats, nudge speed/incline, glance at past sessions. No social, no cloud.

## What it does

- Reads live speed / distance / incline / time / calories over standard Bluetooth FTMS (service `0x1826`).
- Controls start / stop / speed / incline over the treadmill's proprietary 0xFFF0 BLE-UART channel (reverse-engineered from the stock app).
- Auto-connects to the last-used treadmill on launch; falls back to a scan if none is remembered.
- Logs every session to a local Room database; rolling summaries for day / week / month / year.
- Metric / Imperial toggle, since this treadmill's console is mph even though FTMS reports km/h.

## Intended target

**Only tested on the Trailviber `FS-A1139C`.** The FTMS read path should work on any spec-compliant FTMS treadmill, but the control path is specific to this vendor's BLE-UART protocol and will be a no-op on other hardware.

## Build

Open the project in Android Studio. Minimum Android 8 (API 26).

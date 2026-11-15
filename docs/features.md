# Features

## Connect

Over the network:

- **Address** — type `ip:port` directly and tap **Connect**.
- **Scan** — sweeps the local subnet for hosts with ADB port 5555 open and verifies each with a
  real handshake. At the same time it listens for wireless-debugging targets announced over mDNS
  (these use a random port, so the sweep alone cannot find them); those are listed as announced,
  without the handshake check. Shows "Scanning for devices on the local network…" while running,
  "No adb devices found" if nothing turns up.
- **Pair** — wireless debugging's pairing flow: enter the target's pairing `ip:port` and
  6-digit code; shows "Pairing…", then "Pairing succeeded" or "Pairing failed". The address is
  filled in automatically when exactly one device on the network is offering pairing.

Recent network connections are listed newest first (up to 10) for one-tap reconnect, each
removable.

Over a cable:

- **USB-OTG** — lists attached devices that expose a USB debugging interface, asks Android for
  permission to use the one you pick, then connects. The first time, the target shows its "Allow
  USB debugging?" prompt and the app waits ("Waiting for authorisation on the device…") until you
  accept it there; **Cancel** stops waiting. See [Known limitations](known-limitations.md) for
  what has and has not been verified on hardware.

## Device info

A read-only panel: model, manufacturer, serial, device codename, CPU architecture, core count,
Android version, SDK level, security patch, battery (percentage, temperature, charging state),
storage (used/total), screen resolution and density, and Wi-Fi IP — parsed from `getprop`,
`dumpsys battery`, `df`, `wm size`/`wm density`, and `ip addr`.

A **Screenshot** button captures the target's screen (`screencap`) and saves it to this app's
own external files directory.

## App manager

Lists installed apps with filters for **All** / **Third-party** / **System**. Per app:

- Enable/disable (disabling a system app warns first).
- Uninstall, clear data ("App data cannot be recovered once cleared."), clear cache.
- View details: version, min/target SDK, install/update time, permissions.
- Launch.
- Install an APK via the system file picker.
- "Activate" — a shell-permission-granting action that needs root on the target; disabled with
  an explanation when root isn't available.

## File manager

Browses the target's filesystem starting from `/sdcard`, with a toggle for hidden (dot) files.
Per entry: open (directories browse in, files show an info dialog), copy/paste, rename, delete
(with confirmation), and download. Upload pushes a file from the controlling device onto the
target via the system file picker.

## Shell

A persistent interactive terminal session: type a command, see streamed output. **Clear
output** resets the scrollback; **Restart terminal** closes and reopens the session;
**Restart adb server** issues `setprop ctl.restart adbd` (needs root on the target).

## Logs

A live tail of the target's `logcat` (the last 200 lines, then following), coloured by level.
**Pause**/**Resume** holds the list still (lines arriving while paused are skipped), **Clear**
empties it, **Level** sets the minimum level shown (Verbose to Error), and the filter field
matches tag or message text, ignoring case. The view follows the newest line while scrolled
to the bottom and keeps the newest 5000 lines. Leaving the tab ends the remote `logcat`.

## Monitor

A live, auto-refreshing view of the target's screen, built from repeated screenshots rather
than a true video stream — see [Known limitations](known-limitations.md) for how this differs
from full screen mirroring.

## Mirror

Live video of the target's screen with touch control, using the bundled
[scrcpy](https://github.com/Genymobile/scrcpy) server (see [Third-party notices](third-party.md)).
Tap and drag on the video to inject touches (several fingers at once are forwarded by pointer),
and use the **Back**, **Home** and **Recents** buttons below it. The video is H.264, capped at
1280 pixels on its long side, and follows the target's rotation. Leaving the tab stops the
server on the target. If the server cannot start, the screen shows the server's own error
message and a **Try again** button. Video and control only: no audio, clipboard sync or file drop.

## Power

- **Advanced reboot** — normal, recovery, or bootloader, each behind a confirmation.
- **Display DPI** — view the current density, apply a new one (with a warning that it may
  render incorrectly), or reset to the system default.
- **Wireless debugging** — turn it on, or off (with a confirmation that it will disconnect the
  session).

## Process manager

Lists running processes (`ps`) with a kill action per row; killing a system process shows a
stronger warning.

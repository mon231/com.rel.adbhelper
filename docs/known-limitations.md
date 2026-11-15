# Known limitations

## USB-OTG transport

Not implemented. The original app connects over USB-OTG as well as Wi-Fi; this rewrite
currently only supports network targets.

The dependency this project uses for the ADB protocol itself,
[`libadb-android`](https://github.com/MuntashirAkon/libadb-android) (latest published version:
3.1.1), only implements the TCP/TLS transport — there is no USB transport in any version of
that library. Supporting USB-OTG here means implementing the ADB wire protocol's handshake
(`AUTH`/`CNXN`) and framing (`OPEN`/`WRTE`/`OKAY`/`CLSE`) directly over raw
`UsbManager`/`UsbDeviceConnection` bulk-transfer endpoints — a substantial undertaking in its
own right, not a small addition on top of the existing network path.

The **USB-OTG** button on the Connect screen is a visible placeholder: it opens a dialog
explaining that USB isn't supported yet, rather than silently doing nothing or being absent
from the UI. Use a network connection (address entry, Scan, or Pair) in the meantime.

## Screen mirroring (scrcpy)

The **Mirror** tab streams the target's screen with the bundled scrcpy server and forwards
touches, Back, Home and Recents. Limits:

- **No audio**, clipboard sync, file drop or recording; video and touch control only.
- **H.264 only**, at most 1280 pixels on the long side and 4 Mbit/s, tuned for Wi-Fi.
- The target needs **Android 5 or newer**. The server runs as the ADB `shell` user, so apps that
  block screen capture and **DRM-protected content show black**.
- The server version is pinned: the bundled `scrcpy-server.jar` and the client protocol code
  change together, never one without the other (see [Third-party notices](third-party.md)).
- Some vendor ROMs refuse to start the server (`app_process` failures, or an encoder that rejects
  the requested size). The screen then shows the server's error text; a lower size or another
  encoder cannot be chosen from the UI yet.
- Network targets use a second, dedicated ADB connection while Mirror is open, so the target
  sees two ADB clients. USB targets share the existing connection.
- Touch only: no hardware keyboard input, no mouse hover or scroll wheel.
- The decoder and touch path are covered by unit tests only where they are pure logic; they have
  not been run against a real device yet.

The **Monitor** tab remains a lighter alternative: screenshots refreshed in a loop, view only.

## No live-device CI

Connecting to and controlling a second physical Android device isn't something a CI runner can
exercise. The pure logic layer (protocol framing, command builders, text parsers) is unit
tested; everything that actually talks to a target is smoke-tested by hand.

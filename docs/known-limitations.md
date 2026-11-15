# Known limitations

## USB-OTG transport

Implemented, but not yet verified on hardware. The ADB library this project uses
([`libadb-android`](https://github.com/MuntashirAkon/libadb-android) 3.1.1) has no USB transport,
so the app carries its own implementation of the ADB wire protocol for USB (see
[Architecture](architecture.md)).

What is tested: the protocol layer (handshake, key authorisation, stream multiplexing, flow
control, failure handling) runs in unit tests against a simulated device, including a full
command round trip through the same client the network path uses.

What is not: the part that talks to real USB hardware, the Android permission prompt, and
interoperability with a real device's `adbd`. Until that has been confirmed, expect rough edges:

- The controlling phone must support USB host mode (OTG).
- A target that is already in a USB debugging session with a computer cannot be shared.
- Some devices may reject the handshake's checksum mode; the fix is known but not applied
  because it has not been observed.
- One device at a time; plugging a device in does not open the app.
- If a command stalls, the whole USB connection is dropped and re-established on the next
  command, which also ends anything else running over it (a shell, logs, mirroring).

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

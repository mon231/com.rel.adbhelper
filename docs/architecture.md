# Architecture

## Stack

- 100% Kotlin, Jetpack Compose + Material 3, single Activity, Navigation-Compose.
- MVVM: one Gradle module (`:app`), organized as small per-feature packages. Each feature
  package holds its Composable screen, a `ViewModel`, and (where it needs its own I/O) a thin
  service type behind an interface, so the `ViewModel` can be unit-tested with a fake.
- Settings/recents persistence via Jetpack `DataStore`.
- JUnit5 for pure-logic unit tests.

## Transport

For network targets the ADB wire protocol isn't hand-rolled. `core/adb` wraps
[`io.github.muntashirakon:libadb-android`](https://github.com/MuntashirAkon/libadb-android), an
actively maintained open-source ADB client library, behind a small `AdbTransport` interface:

- `NetworkAdbTransport` — the library's TCP/TLS transport against `host:port`, via
  `AdbKeyConnectionManager`, a singleton that owns this app's RSA key pair and self-signed
  certificate (generated with BouncyCastle, not platform hidden APIs).
- `AdbClient` sits on top of `AdbTransport` and exposes the actual command surface
  (`shell`, `raw`, `push`, `pull`, `openService`) that every feature's `ViewModel` calls into —
  everything above this line is transport-agnostic.

- `UsbAdbTransport` (`core/adb/usb`) — the library has no USB transport, so this one speaks the
  ADB wire protocol itself: `AdbMessage` (24-byte header codec, `CNXN`/`AUTH`/`OPEN`/`OKAY`/
  `WRTE`/`CLSE`), `AdbConnection` (handshake, one reader thread, multiplexed streams with `OKAY`
  flow control) and `RsaAdbIdentity` (token signing and adb's public-key format), over an
  `AdbPacketChannel`. `UsbPacketChannel` is the only Android-specific part: bulk transfers on the
  device's ADB interface. It uses the same RSA key as the network transport, so a target
  authorises the app once. Everything except `UsbPacketChannel` is unit-tested against a
  scripted in-memory peer.

## Session

`core/session/DeviceSession` is a small singleton that carries the connected `AdbClient` from
the Connect screen into the device hub (`DeviceScreen`), which hosts one tab per feature.

## Pure logic layer

Everything that can be tested without a live device lives in `core/*` as plain functions:
protocol framing, command builders, and text parsers for `getprop`, `ps`, `ls -la`, and
`pm`/`dumpsys package` output, each covered by a unit test against captured real device output.

## Fastboot

There's no comparable well-known Android-hosted client library for fastboot, so reboot-to-
bootloader flows are expected to shell out to a genuine prebuilt `fastboot` binary via
`ProcessBuilder` once that part of the design lands, rather than a hand-rolled USB fastboot
client.

## Mirroring

The Mirror tab runs the bundled scrcpy server (`assets/scrcpy-server.jar`, pinned to one release;
see `core/scrcpy/README.md`). `core/scrcpy/ScrcpyProtocol` and `ControlMessages` are pure
encoders and parsers for that release's wire format, read through `ByteStreamReader`, which
reassembles fields from arbitrarily sized stream chunks. `ScrcpySession` pushes the jar, starts the
server over a long-lived `shell:` stream, then opens the video socket (the server sends a dummy
byte), the control socket, and only then reads the device name and codec id. It needs three
simultaneous streams, so network targets get their own `AdbKeyConnectionManager.createDisposable`
connection instead of the shared one. In `features/mirror`, `MirrorViewModel` owns the session
and reads packets on a dedicated thread into a `VideoSink`; `VideoDecoder` (`MediaCodec` to a
`SurfaceView`) is the sink and is released and recreated with the surface, asking the server for a
fresh key frame when it loses its place. `mapToVideo` converts touches from view to video
coordinates.

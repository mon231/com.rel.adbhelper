# Plan

Open work only. Delete an item in the same commit that completes it; `git log` on `dev` is the
record of what was done.

## Where things stand

- Branch `dev`, 34 commits ahead of `main` (`2e7c7bb`), not pushed.
- `bash ./gradlew :app:testDebugUnitTest :app:assembleDebug` passes (127 tests).
- Needs `local.properties` with `sdk.dir` (gitignored).
- Nothing on `dev` has been run against a real device.

## Conventions

- Kotlin, Compose + Material 3, JUnit5. ViewModels are plain classes held in `remember`, taking
  `(client: AdbClient, scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined))`.
- Pure logic in `core/<area>/` with a unit test; screens in `features/<area>/`.
- One small commit per item: `fix:` / `feat:` / `ci:` / `chore:` / `docs:`. No new dependencies
  unless a package below says so.
- Read the long comments in `core/adb/AdbClient.kt` before touching transport code: they record
  live-reproduced bugs (the stream-closing watchdog, unreliable end-of-stream in `libadb-android`).

## Order and parallelism

Packages A to D touch disjoint files and run in parallel. E runs after all four are merged.

| Package | What | Owns | Depends on |
|---|---|---|---|
| A | Bug fixes: view models, upload streaming, cleanups | `features/*` (not `features/mirror`), `core/adb/AdbClient.kt`, `core/adb/AdbResult.kt`, `device/DeviceScreen.kt` | none |
| B | Identity fixes, connect screen fixes, mDNS discovery | `core/adb/AdbKeyConnectionManager.kt` (key loading), `core/net/*`, `connect/*` except the USB button area | none |
| C | USB-OTG transport | `core/adb/usb/*`, new `connect/UsbConnect.kt`, USB button area of `connect/ConnectScreen.kt`, manifest `uses-feature`, one accessor in `AdbKeyConnectionManager.kt` | none |
| D | Screen mirroring (scrcpy) | new `core/scrcpy/*`, new `features/mirror/*`, `app/src/main/assets/` | none (tab wired in E) |
| E | Integration: wire Mirror tab, docs, full build, device checklist | `device/DeviceScreen.kt`, `docs/*` | A, B, C, D |

Within a package, items are listed in priority order.

---

## Package A: bug fixes in view models and the client

**A1. DPI input has no bounds (can make the target unusable).** `PowerViewModel.applyDensity`
sends any integer to `wm density`. `wm density 5` leaves a device that cannot be operated by
touch. Accept only 72..1000, show a message otherwise. Test.

**A2. Shell restart can orphan the new session.** In `ShellViewModel`, the old reader coroutine
ends with `stream = null` and posts "Shell session ended" after `restart()` has already started a
new session, so the new stream is dropped (typing does nothing while the UI says connected).
`stream`, `reader`, `closed` are also plain fields written from several threads. Fix: the reader
captures its own stream; it clears the field and posts state only if the field still holds that
same stream; make the fields `@Volatile` or guard them. Test with the existing blocking fake.

**A3. State updates lose writes.** Every view model except `LogsViewModel` does
`_state.value = _state.value.copy(...)` from more than one thread (reader on IO, UI on main).
`clear()` during shell output can be undone by the reader. Replace with `_state.update { }` in
`ShellViewModel`, `FilesViewModel`, `AppsViewModel`, `PowerViewModel`, `DeviceInfoViewModel`,
`ProcessListViewModel`, `LiveScreenViewModel`.

**A4. Shell output breaks lines at chunk boundaries.** Each chunk is one list row, so a line
split across two chunks renders as two rows. Keep a pending partial line and emit whole lines
(same approach as `LogsViewModel.readLines`); cap still 2000 rows. Test.

**A5. Power screen hides a dead link.** `PowerViewModel.load` still uses
`(… as? Success)?.output.orEmpty()`; its `catch` never runs. Use `outputOrThrow()`. Test.

**A6. Selected tab resets on rotation.** `DeviceScreen` uses `remember { mutableIntStateOf(0) }`.
Use `rememberSaveable`.

**A7. Uploads still load the whole file into memory.** `FilesScreen` and `AppsScreen` call
`readBytes()` and `AdbClient.push` takes a `ByteArray`: a 1 GB video cannot be uploaded. Add
`AdbClient.push(path, input: InputStream, chunkSize)` that reads and writes chunk by chunk,
counts bytes, and verifies the count with the existing `stat` check; keep the `ByteArray`
overload as a thin wrapper. Callers pass the `ContentResolver` stream on `Dispatchers.IO`. Test.

**A8. Install failure hides the reason.** `AppsViewModel.install` shows "Install failed" and drops
`pm`'s text (`INSTALL_FAILED_VERSION_DOWNGRADE`, ...). Show the `CommandFailed` message. The
`busy` text is never cleared: clear it when the next action starts. Test.

**A9. Live screen decodes PNGs on the main thread.** `LiveScreenScreen` runs
`BitmapFactory.decodeByteArray` inside composition for every frame. Decode in a
`LaunchedEffect`/`produceState` on `Dispatchers.Default`.

**A10. Files: small correctness.** `paste()` clears `copiedFrom` before the copy result is known
(a failed paste loses the clipboard): clear only on success. `download` overwrites an existing
local file silently: add a numeric suffix. Download and upload failures show no reason: include
the exception message.

**A11. Cleanups.** Move `AdbResult.isRejection()` from `features/power/PowerViewModel.kt` to
`core/adb/AdbResult.kt`, sharing one constant with `AdbClient.runShell` for
`"remote shell closed without an exit status"`. Move `isCompletePng` from
`features/deviceinfo/DeviceInfoViewModel.kt` to `core/` (for example `core/image/Png.kt`) with its
test.

---

## Package B: identity, connect screen, discovery

**B1. First-run key generation race can corrupt the app's ADB identity.**
`AdbKeyConnectionManager.loadOrGenerateKeyPair` is unsynchronised and writes two files
non-atomically. On a fresh install, pressing Scan runs up to five `createDisposable` calls at
once; each generates its own key pair and writes `adb_private.key` / `adb_cert.pem` interleaved,
so the stored key and certificate may not match, permanently (pairing and TLS then fail).
Fix: one `synchronized` lock around load-or-generate, cache the pair in the companion, write each
file to a temp name and rename. On load, if the certificate's public key does not match the
private key, regenerate both. Put the match check in a pure function with a test.

**B2. Certificate expires after one year.** `selfSignedCertificate` sets `notAfter = now + 365d`.
If the TLS stack on either side rejects an expired client certificate, wireless debugging stops
working a year after install. Issue with a long validity (for example 30 years), and on load
re-issue the certificate from the same key when it is expired or within 30 days of expiry (same
key, so paired devices still recognise the app). Test the "needs re-issue" decision.

**B3. Scan can run twice at once.** The Scan button stays enabled while scanning; a second tap
starts a second sweep and both write `scanResults`. Disable while scanning.

**B4. Scan misses every wireless-debugging target (feature: mDNS discovery).** Android 11+
wireless debugging listens on a random port; the sweep only probes 5555. `libadb-android` already
ships `io.github.muntashirakon.adb.android.AdbMdns` (service types `adb`, `adb-tls-connect`,
`adb-tls-pairing`, built on `NsdManager`). Design:
- On Scan, also run `AdbMdns` discovery for `adb-tls-connect` and `adb` for the scan's duration
  (about 5 s), adding each resolved `host:port` to the same "Found devices" list, de-duplicated.
- In the Pair dialog, run discovery for `adb-tls-pairing` while the dialog is open and pre-fill
  the pairing address when exactly one service is found.
- Stop discovery in `onDispose`. Inspect the class with `javap` (aar under
  `~/.gradle/caches/modules-2/files-2.1/com.github.MuntashirAkon/libadb-android/3.1.1/`) for its
  exact constructor and callbacks; check which permissions `NsdManager` discovery needs on current
  Android and add them to the manifest if any.
- Wrap it behind a small interface so the merge-into-results logic is unit-testable.
- Remove the `shortcut:` comment in `LanScanner` that defers mDNS.

**B5.** `connect/LocalSubnet.kt` has an unused `LanScanner` import.

---

## Package C: USB-OTG transport

Goal: tapping **USB-OTG** connects to a target on an OTG cable through the same `AdbTransport`
interface as the network path, so every tab works unchanged.

On `dev` already (`core/adb/usb/`): `AdbMessage.kt` (wire codec, tested), `AdbIdentity.kt` (AUTH
signing and adb public-key encoding, no test), `AdbConnection.kt` (`AdbPacketChannel`, handshake,
reader thread, multiplexed blocking streams with OKAY flow control; no test, never executed).
Treat the two untested files as a draft and fix what the tests expose.

**C1. `AdbIdentityTest`.** RSA-2048 pair generated in the test. `sign`: decrypting the signature
with the public key (`RSA/ECB/PKCS1Padding`) yields the SHA-1 DigestInfo prefix plus the 20-byte
token; a token of another length throws. `encodeAdbPublicKey`: 524 bytes, first word 64,
`n0inv * n[0] == -1 mod 2^32`, modulus and `R^2 mod n` round-trip, exponent 65537. The class doc
claims byte-identical output to the library's package-private `AndroidPubkey`: prove it by
reflection in the test or reword the doc. `publicKeyPayload` ends with ` adbhelper@android\0`.

**C2. `AdbConnectionTest`** against an in-memory scripted peer implementing `AdbPacketChannel`
(`close()` must make a blocked `read` throw). Cases: handshake without auth; with signature auth;
falling back to public key (and `onAwaitingAuthorisation` fires); advertised max payload 0 fails
and a large one is capped; open, data, remote CLSE gives chunks then `null`, each consumed WRTE
answered with OKAY; refused OPEN throws; a write larger than the peer's max payload is split and
waits for OKAY between packets; local `close()` unblocks a blocked `readChunk()` with
`IOException("Stream closed.")` (exact text) without blocking, and a CLSE reaches the peer; two
interleaved streams; channel failure fails all streams and later opens; CNXN mid-session kills the
connection; a packet for an unknown stream is answered with CLSE.

**C3. Adapter and transport** (`core/adb/usb/UsbAdbTransport.kt`).
`ConnectionAdbStream : AdbStream` runs the blocking `write` / `readChunk` on `Dispatchers.IO` via
`runInterruptible`; `close()` delegates. `UsbAdbTransport(openChannel: () -> AdbPacketChannel,
identity: AdbIdentity, onAwaitingAuthorisation: () -> Unit = {}) : AdbTransport` connects lazily
under a `Mutex`, reuses the connection while alive, reconnects otherwise; `invalidate()` closes
it. Add `fun close()`. Tests: lazy connect, reuse, reconnect after `invalidate()`, and
`AdbClient(UsbAdbTransport(...)).shell("echo hi")` end to end against the scripted peer.

**C4. Android USB channel** (`core/adb/usb/UsbPacketChannel.kt`, not unit-testable).
`findAdbInterface(device)`: class `0xFF`, subclass `0x42`, protocol `0x01`, one bulk IN and one
bulk OUT endpoint. `UsbPacketChannel`: `claimInterface(iface, true)`; `write` loops on short
writes, treats a negative return as `IOException`, and sends a zero-length packet after a write
whose length is a non-zero multiple of the OUT endpoint's max packet size; `read` uses the
offset overload of `bulkTransfer` (API 18+) with a finite timeout (1 s) and returns 0 on timeout
so `close()` from another thread always ends the reader; `close` releases the interface and
closes the connection, idempotent. Because reads now time out routinely, replace
`MAX_CONSECUTIVE_EMPTY_READS` in `AdbConnection.readFully` with a check that the channel is still
open (an idle connection must not be killed).

**C5. Identity accessor.** Add to `AdbKeyConnectionManager` only:
`fun usbIdentity(): AdbIdentity = RsaAdbIdentity(privateKey, certificate.publicKey as RSAPublicKey)`.
The same key is used so a device authorises the app once for both transports.

**C6. Connect screen** (`connect/UsbConnect.kt`; in `ConnectScreen.kt` only replace
`UsbNotImplementedDialog` and its call). List `UsbManager.deviceList` entries with an ADB
interface; none shows "No USB device with debugging enabled found. Connect the target with an OTG
cable and enable USB debugging." Request permission with a `PendingIntent.getBroadcast` whose
intent has an app-private action and `setPackage(packageName)`, `FLAG_MUTABLE` on API 31+;
receiver via `ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)`, unregistered on result
and in `onDispose`. On grant: "Connecting…", then "Waiting for authorisation on the device…" when
`onAwaitingAuthorisation` fires; run `AdbClient(transport).shell("echo connected")`; on success
`DeviceSession.attach("usb:<product name>", client)` and `onConnected()`; on failure show the
message and close the transport. No overall timeout (a person must tap Allow): a Cancel button
closes the channel. USB targets are not added to recents. Manifest:
`<uses-feature android:name="android.hardware.usb.host" android:required="false" />`.

Out of scope: auto-launch on cable attach, several devices at once, `shell,v2`, TLS over USB.

Hardware risks: devices that send checksum 0 (then advertise version `0x01000001` and skip the
check when the peer's version is at least that); a target already holding a USB debugging session
with a PC; phones without OTG host mode.

---

## Package D: screen mirroring with scrcpy

Goal: the **Mirror** tab shows the target's screen as live video and forwards touches, Back, Home
and Recents. View and control only: no audio, no clipboard, no file drop.

**Research.** scrcpy's client/server protocol is internal and version-locked: the server refuses
a client whose version string differs, and socket count, order and wire format change between
releases. Latest release is v4.0 (May 2026). So: pin one release, bundle that exact
`scrcpy-server` binary, and implement against `doc/develop.md` and the server source **at that
tag**, not from memory or `master`.

**D1. Bundle the server.** Download `scrcpy-server-v<version>` from the official GitHub release,
verify its SHA-256 against the value published in that release's notes, and add it as
`app/src/main/assets/scrcpy-server.jar` with `SCRCPY_VERSION` in code. Add the Apache-2.0 notice
to `docs/` and the About/README credits. Record version, URL and checksum in
`core/scrcpy/README.md` so the file can be re-verified.

**D2. Wire formats, pure Kotlin, unit-tested** (`core/scrcpy/`). From the pinned tag: server
start arguments; the per-socket preamble (dummy byte on a forward tunnel, device name, codec
metadata: codec id, width, height); the video frame header (PTS with config and key-frame flag
bits, payload size); control messages needed here: inject touch event (action, pointer id,
position, screen size, pressure, buttons), inject keycode (Back, Home, App switch), and
back-or-screen-on. Encoders and decoders take and return byte arrays; tests use byte vectors
derived from the documentation at that tag.

**D3. Session** (`core/scrcpy/ScrcpySession.kt`). Steps:
1. Push the asset to `/data/local/tmp/scrcpy-server.jar` with `AdbClient.push` (skip when a file
   of the same size is already there).
2. Start it over a long-lived `openService("shell:CLASSPATH=/data/local/tmp/scrcpy-server.jar
   app_process / com.genymobile.scrcpy.Server <version> tunnel_forward=true audio=false
   control=true cleanup=true scid=<random 31-bit hex> max_size=1280 video_codec=h264 ...")`;
   keep reading its output for error lines.
3. Open `localabstract:scrcpy_<scid>` for video, then again for control (order as the pinned
   version requires), retrying the first open for up to about 5 s while the server starts.
4. Expose video as a callback or flow of `(header, payload)` and `sendControl(bytes)`.
5. `close()` closes the control, video and server streams (off the main thread); `cleanup=true`
   makes the server delete itself.
   Connection choice: this needs three simultaneous streams, and `AppsViewModel.load`'s comment
   records that concurrent reads on the shared `libadb-android` connection misbehave. When
   `DeviceSession.target` parses as `host:port`, run the session on its own connection
   (`NetworkAdbTransport` with `AdbKeyConnectionManager.createDisposable`, disconnected on close);
   otherwise (USB) use the session client, whose transport multiplexes properly.
   Tested with a fake transport scripted per service string.

**D4. Decoder and screen** (`features/mirror/`). `MirrorViewModel` owns the session and state
(connecting, running with video size, error). `MirrorScreen()` hosts a `SurfaceView` through
`AndroidView`, sized to the video aspect ratio. A `MediaCodec` H.264 decoder is configured from
the codec metadata and the first config packet, fed on a dedicated thread, rendering to the
surface; recreate it when the surface is recreated or the size changes (device rotation sends new
metadata, per the pinned protocol). Touch: map `MotionEvent` positions from view to video
coordinates and send down / move / up, multi-touch by pointer id. A bottom row with Back, Home,
Recents. Start in `LaunchedEffect`, close in `DisposableEffect`. On error show the server's
message. `MirrorScreen()` must be callable with no arguments; do not edit `DeviceScreen.kt`.

**D5. Docs.** `docs/features.md` (Mirror), `docs/known-limitations.md` (replace the "not
implemented" section with real limits: no audio, H.264 only, target needs Android 5+, DRM
content is black), `docs/architecture.md` (short section).

Risks: decoder and touch mapping cannot be unit-tested; some targets need `video_encoder`
selection or a lower `max_size`; `app_process` start can fail on vendor ROMs (surface the
server's stderr).

---

## Package E: integration (after A to D)

1. Merge all four; resolve `ConnectScreen.kt`, `AndroidManifest.xml` and
   `AdbKeyConnectionManager.kt` overlaps (B and C both touch them, in different places).
2. In `device/DeviceScreen.kt` replace the private `MirrorScreen` stub with
   `features.mirror.MirrorScreen`.
3. Docs for USB: `docs/known-limitations.md`, `docs/features.md` (Connect), `docs/architecture.md`
   (Transport). Say what is unit-tested and what was verified on hardware.
4. `bash ./gradlew build` and `mkdocs build`.
5. Remove leftover agent worktrees and branches (`.claude/worktrees/agent-*`,
   `worktree-agent-*`) once their commits are confirmed on `dev`.

---

## Needs the repository owner

**Release signing.** Workflows sign with the project's keystore from four GitHub Actions secrets:
`SIGNING_KEYSTORE_BASE64`, `SIGNING_KEYSTORE_PASSWORD`, `SIGNING_KEY_ALIAS`,
`SIGNING_KEY_PASSWORD` (`docs/contributing.md`, "Release signing").
- [ ] Create the keystore, add the secrets, keep an offline backup.
- [ ] Push a test tag; confirm the `android` job signs and `apksigner verify` passes (the new
      steps have only been checked for valid YAML).

Until then a `v*` tag fails on purpose and `build.yml` uploads a debug-signed APK. New APKs will
not install over `v0.1.0`: users uninstall once; say so in the release notes.

**Verify on a real device** (none of this could be exercised without a target):
- [ ] Rotate and toggle dark mode while connected: no crash, same tab, hub stays connected.
- [ ] Kill the app in the background on the hub, reopen: lands on Connect.
- [ ] Upload and install files over 50 MB; upload into a read-only folder shows a failure
      (`stat -c %s` must exist on the oldest target Android you care about).
- [ ] Transfer a file whose name contains `'` and a space.
- [ ] Reboot, wireless debugging off, "Restart adb server" show their success message.
- [ ] Shell: restart several times, type after each; leave and re-enter the tab, no stray `sh`.
- [ ] Logs: streams, filters work, leaving the tab ends the remote `logcat`.
- [ ] Processes tab renders, kill works. From `/`, tapping `sdcard` opens it.
- [ ] Scan finds a 5555 target and a wireless-debugging target; Pair pre-fills the address.
- [ ] Fresh install, Scan first, then pair and connect: identity is consistent.
- [ ] USB-OTG: first connection (Allow prompt), reconnect without prompt, every tab, cable pull.
- [ ] Mirror: video appears, touch lands where tapped in both orientations, Back/Home/Recents,
      leaving the tab stops the server (`ps -A | grep app_process`).

**Before merging `dev` to `main`:** packages A to E done or explicitly dropped from the release
notes; signing secrets in place; `bash ./gradlew build` green locally and in CI.

---

## Later (not scheduled)

Auto-reconnect and a "connection lost" banner on the hub; a Disconnect action; several device
sessions; shell command history; file multi-select; APK extract and app backup; R8 minification
(release APK size); keeping view models across rotation (androidx `ViewModel`); storing the ADB
private key in the Android Keystore; fastboot.

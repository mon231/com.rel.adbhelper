# Plan

Hand-off for whoever picks this up next. Everything listed here is still open; finished items have
been removed (see `git log` on `dev` for what was done). Delete an item from this file in the same
commit that completes it.

## Where things stand

- Branch: `dev`, based on `main` (`2e7c7bb`). Not pushed.
- `bash ./gradlew :app:testDebugUnitTest :app:assembleDebug` passes: 127 tests, 0 failures.
- Needs `local.properties` with `sdk.dir` (gitignored).
- Nothing on this branch has been run against a real device yet.
- Done since `main`: audit findings 1-3 and 5-19 (crash on rotation, upload memory, shell quoting,
  install timeout, dead-link reporting, Processes tab, push verification, shell session leak, reboot
  messages, truncated screenshots, symlinked folders, dead code, recents order), the Logs tab, and
  the first half of the USB transport (below).

## Conventions

- Kotlin, Compose + Material 3, JUnit5. ViewModels are plain classes held in `remember`, taking
  `(client: AdbClient, scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined))`.
- Pure logic goes in `core/<area>/` with a unit test; screens in `features/<area>/`.
- One small commit per item, message `fix:` / `feat:` / `ci:` / `chore:`. No new dependencies.
- Read the long comments in `core/adb/AdbClient.kt` before touching the transport: they record
  live-reproduced bugs (the stream-closing watchdog, unreliable end-of-stream in `libadb-android`).

---

## 1. USB-OTG transport (feature, in progress)

Goal: tapping **USB-OTG** on the Connect screen connects to a target attached by OTG cable, using
the same `AdbTransport` interface as the network path, so every tab works unchanged.

### Already on `dev` (package `core/adb/usb/`)

| File | State |
|---|---|
| `AdbMessage.kt` | Wire codec: `AdbWire` constants, `AdbMessage`, `AdbHeader`, `adbChecksum`. Tested by `AdbMessageTest`. |
| `AdbIdentity.kt` | `AdbIdentity` interface and `RsaAdbIdentity` (AUTH token signing, adb public-key encoding). **No test.** |
| `AdbConnection.kt` | `AdbPacketChannel` interface, `AdbConnection` (handshake, reader thread, stream multiplexing), `AdbConnectionStream` (blocking read/write with OKAY flow control). **No test, never executed.** |

Treat the two untested files as a draft: review them while writing the tests, and fix what the
tests expose.

### Steps

**1a. `AdbIdentityTest`** (`app/src/test/kotlin/com/rel/adbhelper/core/adb/usb/`)
- Generate an RSA-2048 key pair in the test.
- `sign`: the result verifies with `Signature.getInstance("SHA1withRSA")`-style check, i.e.
  decrypting with the public key (`Cipher "RSA/ECB/PKCS1Padding"`) yields the SHA-1 DigestInfo
  prefix followed by the 20-byte token. A token that is not 20 bytes throws.
- `encodeAdbPublicKey`: 524 bytes; first word is 64; `n0inv * n[0] == -1 mod 2^32`; modulus and
  `R^2 mod n` round-trip from little-endian; exponent is 65537.
- The class doc claims byte-identical output to `libadb-android`'s package-private
  `AndroidPubkey`. Either prove it (reflection on the library class in the test) or reword the doc.
- `publicKeyPayload` ends with ` adbhelper@android\0`.

**1b. `AdbConnectionTest`** with an in-memory scripted peer implementing `AdbPacketChannel`
(two blocking queues; `close()` must make a blocked `read` throw). Cases:
- Handshake with no auth (peer answers CNXN at once); `peerMaxPayload` and `peerBanner` are set.
- Handshake with signature auth (AUTH TOKEN, then CNXN after AUTH SIGNATURE).
- Fallback to public key (second AUTH TOKEN leads to AUTH RSAPUBLICKEY and `onAwaitingAuthorisation`).
- Peer advertising a max payload of 0 fails; a larger one is capped at `AdbWire.MAX_PAYLOAD`.
- `open` then data then remote CLSE: `readChunk` returns the chunks, then `null`. Each consumed
  WRTE is answered with OKAY.
- Refused OPEN (peer answers CLSE) throws `IOException`.
- `write` larger than `peerMaxPayload` is split, and each WRTE waits for the peer's OKAY.
- Local `close()` while `readChunk()` is blocked on another thread: it throws
  `IOException("Stream closed.")` (exact text; `AdbClient.raw` matches on it). `close()` returns
  without blocking and a CLSE reaches the peer.
- Two streams interleaved keep their data apart.
- Channel failure fails every open stream and every later `open`.
- A CNXN arriving mid-session kills the connection.
- A packet for an unknown local id is answered with CLSE.

**1c. Suspending adapter and transport** (new `core/adb/usb/UsbAdbTransport.kt`)
- `ConnectionAdbStream : AdbStream` wrapping `AdbConnectionStream`: `write` and `readChunk` run on
  `Dispatchers.IO` via `runInterruptible` (the blocking calls use `Condition.await`, so interruption
  is what makes coroutine cancellation work); `close()` delegates directly (it never blocks).
- `UsbAdbTransport(openChannel: () -> AdbPacketChannel, identity: AdbIdentity, onAwaitingAuthorisation: () -> Unit = {}) : AdbTransport`:
  connects lazily on the first `open` under a `Mutex`, reuses the connection while `isAlive`,
  reconnects otherwise. `invalidate()` closes the connection so the next `open` reconnects.
- Taking a channel factory rather than Android classes keeps it unit-testable: test lazy connect,
  reuse, reconnect after `invalidate()`, and that `AdbClient(UsbAdbTransport(...)).shell("echo hi")`
  works end to end against the scripted peer from 1b.

**1d. Android USB channel** (new `core/adb/usb/UsbPacketChannel.kt`; cannot be unit-tested)
- `findAdbInterface(device: UsbDevice): UsbInterface?`: class `0xFF`, subclass `0x42`, protocol
  `0x01`, with one bulk IN and one bulk OUT endpoint.
- `UsbPacketChannel(connection: UsbDeviceConnection, iface: UsbInterface) : AdbPacketChannel`:
  `claimInterface(iface, true)` in the factory, failing with a clear `IOException` if refused.
  - `write`: loop on short writes with `bulkTransfer(out, bytes, offset, length, 0)`; a negative
    return is an `IOException`. When the length is a non-zero exact multiple of
    `out.maxPacketSize`, send a zero-length packet afterwards.
  - `read`: `bulkTransfer(in, buffer, offset, length, 0)`; negative return is an `IOException`.
    The 5-argument offset overload exists from API 18, so minSdk 26 is fine.
  - `close`: `releaseInterface` then `connection.close()`, idempotent. Closing from another thread
    is what unblocks a transfer with timeout 0; confirm on hardware (see risks).

**1e. Identity from the app's existing key**
- The key lives in `AdbKeyConnectionManager` (`adb_private.key` PKCS8 and `adb_cert.pem` in
  `filesDir`), behind private members. Add the smallest accessor that returns an `AdbIdentity`,
  for example `fun usbIdentity(): AdbIdentity = RsaAdbIdentity(privateKey, certificate.publicKey as RSAPublicKey)`.
  The same key must be used so a device authorises this app once for both transports.

**1f. Connect screen** (new `connect/UsbConnect.kt`; keep edits to `ConnectScreen.kt` to the
USB button and dialog)
- Replace `UsbNotImplementedDialog`. On tap, list `UsbManager.deviceList` entries that have an ADB
  interface. None: show "No USB device with debugging enabled found. Connect the target with an OTG
  cable and enable USB debugging."
- Permission: if `!usbManager.hasPermission(device)`, call `requestPermission` with a
  `PendingIntent.getBroadcast` whose intent has an app-private action and `setPackage(packageName)`.
  Flags: `FLAG_MUTABLE` on API 31+ (the system adds extras). Register the receiver with
  `ContextCompat.registerReceiver(..., ContextCompat.RECEIVER_NOT_EXPORTED)` (androidx.core 1.17.0
  is already a dependency) and unregister it in `onDispose` and after the result.
- On grant: status "Connecting…", then "Waiting for authorisation on the device…" once
  `onAwaitingAuthorisation` fires. Run `AdbClient(transport).shell("echo connected")`. On success
  `DeviceSession.attach("usb:${device.productName ?: device.deviceName}", client)` and call
  `onConnected()`. On failure show the message and close the transport.
- First-time authorisation waits for a person to tap Allow, which can exceed
  `CONNECT_TIMEOUT_MILLIS` (25 s). Do not route this through `ConnectViewModel`'s timeout; give
  the USB attempt a Cancel button that closes the channel instead.
- Do not add USB targets to recents.
- `AndroidManifest.xml`: `<uses-feature android:name="android.hardware.usb.host" android:required="false" />`.

**1g. Docs**: `docs/known-limitations.md` (USB section), `docs/features.md` (Connect section),
`docs/architecture.md` (Transport section). State that the protocol layer is unit-tested against a
simulated peer and say what was verified on hardware.

### Out of scope
Auto-launch on cable attach (intent filter), several devices at once, `shell,v2` protocol, TLS
(`STLS`) over USB.

### Risks to check on hardware
- A `bulkTransfer` blocked with timeout 0 may not return when the `UsbDeviceConnection` is closed
  on some devices. If so, use a finite read timeout (for example 1 s) and loop, treating a timeout
  as "no data"; `readFully` already tolerates empty reads, but its `MAX_CONSECUTIVE_EMPTY_READS`
  limit of 1000 would then end an idle connection after about 17 minutes and must be removed or
  made time-based.
- Version `0x01000000` requires payload checksums in both directions. A device that sends
  checksum 0 fails `AdbHeader.toMessage`. If that shows up, advertise `0x01000001` and skip the
  check when the peer's CNXN version is `0x01000001` or higher.
- Devices that already hold a USB debugging session with a PC, and phones whose OTG port does not
  supply host mode.

---

## 2. Release signing: needs the repository owner

The workflows now sign with the project's own keystore, read from four GitHub Actions secrets:
`SIGNING_KEYSTORE_BASE64`, `SIGNING_KEYSTORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`.
`docs/contributing.md` ("Release signing") has the `keytool` commands.

- [ ] Owner: create the keystore, add the four secrets, keep an offline backup of the keystore.
- [ ] Push a test tag and confirm the `android` job signs and `apksigner verify` passes; the new
      signing steps have only been checked for valid YAML, never run.

Until the secrets exist, a `v*` tag fails on purpose at "Require signing secrets", and `build.yml`
uploads the debug-signed APK. APKs signed with the new key will not install over `v0.1.0`
(signed with the old public key): users uninstall once. Say so in the next release notes.

---

## 3. Verify on a real device

None of the fixes below could be exercised without a target. Check each, fix what fails:

- [ ] Rotate the phone and toggle dark mode while connected: no crash, hub stays connected.
- [ ] Kill the app in the background while on the hub, reopen: lands on Connect.
- [ ] Upload a file over 50 MB; install an APK over 50 MB (5-minute install timeout).
- [ ] Upload into a read-only folder shows a failure. `push` verifies with `stat -c %s`; confirm
      the target's toybox supports it on the oldest Android version you care about.
- [ ] Download and upload a file whose name contains `'` and a space.
- [ ] Reboot, wireless debugging off, and "Restart adb server" show their success message.
- [ ] Leave and re-enter the Shell tab several times: one remote `sh` per visit, none left behind
      (`ps -A | grep sh` from a PC).
- [ ] Logs tab: lines stream, level and text filters work, leaving the tab ends the remote
      `logcat`, the UI stays responsive under heavy logging, `-T 200` is accepted by the target.
- [ ] Processes tab renders and kill works.
- [ ] From `/`, tap `sdcard`: opens the folder. Paste works at `/`.
- [ ] Screenshot saves a valid PNG; Monitor tab still updates.
- [ ] Scan still finds devices (own IP is now skipped). Recents show newest first, max 10, and an
      existing list from `v0.1.0` survives the upgrade.

---

## 4. Small cleanups

- `AdbResult.isRejection()` lives in `features/power/PowerViewModel.kt` and is imported by
  `features/shell/ShellViewModel.kt`. It compares against the literal
  `"remote shell closed without an exit status"` produced in `AdbClient.runShell`. Move the helper
  next to `AdbResult` and share one constant with `AdbClient` so the two cannot drift.
- `isCompletePng` lives in `features/deviceinfo/DeviceInfoViewModel.kt` and is imported by
  `features/livescreen`. Move it to `core/`.
- `connect/LocalSubnet.kt` has an unused `LanScanner` import.
- `docs/features.md`: add the Processes tab entry to match the hub, if missing.
- Leftover agent worktrees and branches (`.claude/worktrees/agent-*`, `worktree-agent-*`): all
  their commits are on `dev`; remove them once that is confirmed.

---

## 5. Before merging `dev` to `main`

- [ ] Items 1 and 3 done, or USB left out of the release notes.
- [ ] Signing secrets in place (item 2).
- [ ] `bash ./gradlew build` green locally and in CI.
- [ ] `mkdocs build` succeeds with the updated docs.

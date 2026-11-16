# Plan

Open work only. Delete an item in the same commit that completes it; `git log` on `dev` is the
record of what was done.

## Where things stand

- Branch `dev`, based on `main` (`2e7c7bb`).
- `bash ./gradlew build` passes (248 unit tests, lint, debug and release variants).
- All planned code work is merged: audit fixes, Logs tab, mDNS discovery, USB-OTG transport,
  scrcpy mirroring, forced left-to-right layout, protected ADB identity.
- Tested by hand on two Android 17 (API 37) emulators, app on one and target on the other, over
  the network (2026-11-16): upgrade over an older build keeps the ADB key; Connect, Scan, Pair
  dialog, USB dialog (no device); Info, Apps, Files, Shell, Processes, Logs, Power, Monitor and
  Mirror; rotation, process death, Hebrew system locale (app stays left-to-right).
- **Not run on real hardware**, and USB-OTG has only been tested against a simulated device.

## Conventions

- Kotlin, Compose + Material 3, JUnit5. ViewModels are plain classes held in `remember`, taking
  `(client: AdbClient, scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined))`.
- Pure logic in `core/<area>/` with a unit test; screens in `features/<area>/`.
- One small commit per item: `fix:` / `feat:` / `ci:` / `chore:` / `docs:`. No new dependencies.
- Read the long comments in `core/adb/AdbClient.kt` before touching transport code.
- The app is always left-to-right (owner's decision); never add right-to-left support.
- Never replace a readable ADB private key (`AdbKeyConnectionManager`); devices recognise the
  app by it.

---

## 1. Open bugs (found on the emulators)

**1b. One unexplained "Connection to the device was lost".** The Power tab showed it once, right
after leaving the Logs tab; six further attempts (from Logs and from Shell) were fine. Suspect a
race between closing a long-lived stream and the next command on the shared connection. Needs a
way to reproduce before anyone changes code. Read through, no defect found: libadb's
`openStream`/`disconnect`/`isConnected` share one lock and each stream has its own id, so closing
the Logs stream cannot corrupt a concurrent open, and nothing in the Logs/Shell path calls
`markPoisoned` (only the watchdogs do, and the Logs stream has none). Failures now carry their
cause on screen and in logcat (tag `AdbClient`); if it recurs, read that text first.

**1c. One unexplained stall of the large upload path** (the same event as 1a): a write blocked
longer than the 15 s watchdog mid-transfer. If it recurs, check whether it is the emulator link or
the library's flow control before changing the timeout.

---

## 2. Verify on real hardware, fix what fails

Not coverable on the emulators:
- [ ] The owner's report: a device that used to connect no longer does after updating. Not
      reproduced. Needs the device, its address and the message shown. If the previous `dev`
      build regenerated the key on that phone, the target must authorise or pair once more.
- [ ] A file over 1 GB uploads; upload into a read-only folder shows a failure (`stat -c %s`
      must exist on the oldest target Android you care about; so far only Android 17 was tried).
- [ ] Wireless debugging off and "Restart adb server" show their success message (Reboot does).
- [ ] A failed install shows `pm`'s reason.
- [ ] Targets older than Android 17: file listing (`ls -laN` with fallback), shell, logcat
      `-T 200`, mirroring.

Connect:
- [ ] Scan finds a wireless-debugging target (random port) by mDNS on a real network; on the
      emulators it found a port-5555 device on the LAN by mDNS. No extra manifest permission was
      added for discovery; confirm on Android 13 to 16.
- [ ] Pair with a real code; the dialog pre-fills the address when one device offers pairing.
- [ ] Fresh install, press Scan first, then pair and connect: the identity is consistent.

USB-OTG (never run on hardware):
- [ ] First connection shows the target's Allow prompt; Cancel while waiting works.
- [ ] Reconnect without a prompt; every tab works; pulling the cable shows an error, not a hang.
- [ ] Leaving the hub releases the device (another app, or a second connect, can claim it).
- [ ] A controlling phone on Android 8 to 11 (API 26 to 30).
- [ ] If a device rejects the handshake: advertise version `0x01000001` and skip the checksum
      check when the peer's version is at least that (`core/adb/usb/AdbMessage.kt`).

Mirror (works on the emulator: video, touch, Back, Home, server stops on leaving):
- [ ] Real devices and older Android versions; a USB target.
- [ ] Touch after rotating the TARGET (only the controller was rotated).
- [ ] If the decoder shows nothing on some device: split SPS/PPS into `csd-0` / `csd-1`
      (`features/mirror/VideoDecoder`); if the encoder is rejected, lower `max_size`.
- [ ] Over USB, a stalled command drops the whole connection and with it the mirror session;
      decide whether mirroring needs protection from that.

---

## 3. Housekeeping

- [ ] Remove leftover agent worktrees and branches (`.claude/worktrees/agent-*`,
      `worktree-agent-*`, and the old `adbhelper-scaffold`); all their commits are on `dev`.
- [ ] When bumping scrcpy: replace the asset, update `core/scrcpy/README.md` (version, URL,
      SHA-256) and re-check the wire formats against that tag; the protocol is version-locked.

## 4. Before merging `dev` to `main`

- [ ] Section 2 done for whatever the release notes claim; unverified features labelled as such.
- [ ] CI green on `dev`.

Signing stays on `signapp` from PyPI with its built-in keystore (owner's decision), so builds
keep installing over `v0.1.0`.

---

## Later (not scheduled)

Auto-reconnect and a "connection lost" banner on the hub; a Disconnect action; ADB handshake
check for mDNS-found targets; several device sessions; shell command history; file multi-select;
APK extract and app backup; mirror audio and clipboard; R8 minification (APK size); keeping view
models across rotation (androidx `ViewModel`); storing the ADB private key in the Android
Keystore; fastboot.

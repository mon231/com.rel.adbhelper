# Plan

Open work only. Delete an item in the same commit that completes it; `git log` on `dev` is the
record of what was done.

## Where things stand

- Branch `dev`, based on `main` (`2e7c7bb`), not pushed.
- `bash ./gradlew build` passes (228 unit tests, lint, debug and release variants) and
  `mkdocs build` succeeds.
- All code work planned so far is merged: the audit fixes, Logs tab, mDNS discovery, USB-OTG
  transport and scrcpy mirroring.
- **Nothing on `dev` has been run against a real device.** Everything below needs either the
  repository owner or hardware.

## Conventions

- Kotlin, Compose + Material 3, JUnit5. ViewModels are plain classes held in `remember`, taking
  `(client: AdbClient, scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined))`.
- Pure logic in `core/<area>/` with a unit test; screens in `features/<area>/`.
- One small commit per item: `fix:` / `feat:` / `ci:` / `chore:` / `docs:`. No new dependencies.
- Read the long comments in `core/adb/AdbClient.kt` before touching transport code.

---

## 1. Release signing (repository owner)

Workflows sign with the project's keystore from four GitHub Actions secrets:
`SIGNING_KEYSTORE_BASE64`, `SIGNING_KEYSTORE_PASSWORD`, `SIGNING_KEY_ALIAS`,
`SIGNING_KEY_PASSWORD` (`docs/contributing.md`, "Release signing").

- [ ] Create the keystore, add the secrets, keep an offline backup.
- [ ] Push a test tag; confirm the `android` job signs and `apksigner verify` passes (the new
      steps have only been checked for valid YAML).

Until then a `v*` tag fails on purpose and `build.yml` uploads a debug-signed APK. New APKs will
not install over `v0.1.0`: users uninstall once; say so in the release notes.

---

## 2. Verify on a real device, fix what fails

General:
- [ ] Rotate and toggle dark mode while connected: no crash, same tab, hub stays connected.
- [ ] Kill the app in the background on the hub, reopen: lands on Connect.
- [ ] Upload and install files over 50 MB, and one over 1 GB; upload into a read-only folder
      shows a failure (`stat -c %s` must exist on the oldest target Android you care about).
- [ ] Transfer a file whose name contains `'` and a space.
- [ ] Reboot, wireless debugging off, "Restart adb server" show their success message.
- [ ] DPI: values outside 72..1000 are refused.
- [ ] Shell: the prompt is visible; restart several times and type after each; leave and re-enter
      the tab, no stray `sh` on the target.
- [ ] Logs: streams, filters work, leaving the tab ends the remote `logcat`.
- [ ] Processes tab renders, kill works. From `/`, tapping `sdcard` opens it.
- [ ] A failed install shows `pm`'s reason.

Connect:
- [ ] Scan finds a port-5555 target and a wireless-debugging target (mDNS). Confirm discovery
      works on Android 13 to 16 with the current manifest (no extra permission was added; this
      was not checked against current documentation).
- [ ] Pair dialog pre-fills the address when one device offers pairing.
- [ ] Fresh install, press Scan first, then pair and connect: the identity is consistent.
- [ ] Recents: newest first, max 10, a list from `v0.1.0` survives the upgrade.

USB-OTG (only the protocol layer is tested, against a simulated device):
- [ ] First connection shows the target's Allow prompt; Cancel while waiting works.
- [ ] Reconnect without a prompt; every tab works; pulling the cable shows an error, not a hang.
- [ ] Leaving the hub releases the device (another app, or a second connect, can claim it).
- [ ] A controlling phone on Android 8 to 11 (API 26 to 30).
- [ ] If a device rejects the handshake: advertise version `0x01000001` and skip the checksum
      check when the peer's version is at least that (`core/adb/usb/AdbMessage.kt`).

Mirror (codecs and session are tested with fakes; decoder, surface and touch never ran):
- [ ] Video appears on a network target and on a USB target.
- [ ] Touch lands where tapped, in both orientations and after rotating the target.
- [ ] Back, Home, Recents work.
- [ ] Leaving the tab stops the server (`ps -A | grep app_process` on the target).
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
- [ ] Signing secrets in place (section 1).
- [ ] CI green on `dev`.

---

## Later (not scheduled)

Auto-reconnect and a "connection lost" banner on the hub; a Disconnect action; ADB handshake
check for mDNS-found targets; several device sessions; shell command history; file multi-select;
APK extract and app backup; mirror audio and clipboard; R8 minification (APK size); keeping view
models across rotation (androidx `ViewModel`); storing the ADB private key in the Android
Keystore; fastboot.

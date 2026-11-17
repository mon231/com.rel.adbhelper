# Features

Everything the app implements, derived from the source. Status values:

- **Verified on emulator**: hand-tested (2026-11-16/17) on two Android 17 (API 37) emulators, app on one and target on the other, over the network.
- **Verified in tests only**: covered by unit tests or by `RealAdbdIntegrationTest` against a real `adbd`, never run end to end on hardware.
- **Unverified on real hardware**: implemented, never run on a physical device. See [Known limitations](../known-limitations.md).

| Feature | Where in the app | How it works (one line) | Status | Page |
|---|---|---|---|---|
| Connect by address | Connect screen, address field and **Connect** | `ip:port` over the ADB library's TCP/TLS transport, RSA key auth | Verified on emulator | [Connect](connect.md) |
| Recent connections | Connect screen, **Recent** list | Last 10 network targets in DataStore, newest first, removable | Verified on emulator | [Connect](connect.md) |
| Scan | **Scan** | Subnet sweep of port 5555 with handshake check, plus an mDNS listener | Verified on emulator; mDNS on a real wireless-debugging port unverified on real hardware | [Connect](connect.md) |
| Pair with code | **Pair** | Wireless-debugging pairing with address and 6-digit code | Dialog verified on emulator; pairing with a real code unverified on real hardware | [Connect](connect.md) |
| USB-OTG | **USB-OTG** | App's own ADB-over-USB stack: Android permission, then the target's Allow prompt | Verified in tests only (protocol against real `adbd` over TCP); USB transfers unverified on real hardware | [Connect](connect.md) |
| Device hub | After connecting: nine tabs | Tabs share one `AdbClient`; leaving detaches the session | Verified on emulator | [Device hub](hub.md) |
| Disconnect, connection-lost banner, Reconnect | Top of the hub | Health derived from command results; Reconnect runs `echo ok` | Verified in tests only | [Device hub](hub.md) |
| Info | **Info** tab | `getprop`, `dumpsys battery`, `df`, `wm`, `ip addr`; Screenshot via `screencap` | Verified on emulator | [Info](info.md) |
| Apps | **Apps** tab | `pm` commands; install streams the APK then runs `pm install -r` | Verified on emulator; failed-install message unverified on real hardware | [Apps](apps.md) |
| Files | **Files** tab | `ls`, `cp`, `mv`, `rm`; push and pull as `exec:cat` streams with size checks | Verified on emulator; over 1 GB and read-only folder unverified on real hardware | [Files](files.md) |
| Shell | **Shell** tab | Interactive `shell:` stream | Verified on emulator | [Shell](shell.md) |
| Processes | **Processes** tab | `ps -A`, `kill <pid>` | Verified on emulator | [Processes](processes.md) |
| Logs | **Logs** tab | `logcat -v threadtime -T 200` stream with level and text filters | Verified on emulator | [Logs](logs.md) |
| Power | **Power** tab | `reboot`, `wm density`, `settings put global adb_wifi_enabled` | Reboot verified on emulator; Wireless debugging off unverified on real hardware | [Power](power.md) |
| Monitor | **Monitor** tab | Repeated `screencap -p` screenshots | Verified on emulator | [Monitor](monitor.md) |
| Mirror | **Mirror** tab | Bundled scrcpy server v5.0.1, H.264 decoded with `MediaCodec`, touch and keys sent back | Verified on emulator; real devices unverified on real hardware | [Mirror](mirror.md) |
| ADB identity (key handling) | Automatic | KEEP / REISSUE_CERT / REGENERATE; a readable key is never replaced | Verified in tests; upgrade keeps the key verified on emulator | [Identity and layout](platform.md) |
| Forced left-to-right layout | Automatic | `LocalLayoutDirection` forced to Ltr in `MainActivity` | Verified on emulator (Hebrew locale) and in a UI test | [Identity and layout](platform.md) |
| Supported Android versions | n/a | minSdk 26, targetSdk 36 | Only Android 17 targets tried | [Identity and layout](platform.md) |
| Own stack (debug) button | Connect screen, debug builds only | Connects through the USB stack over TCP, for testing | Debug-only, absent from release builds | [Testing](../testing.md) |

The app is English only and has no ads, accounts or premium gating.

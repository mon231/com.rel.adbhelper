# Known limitations

## Not verified on real hardware

All hand testing used two Android 17 emulators. These were never run on a physical device:

- **USB-OTG**: the protocol layer is proven against a real `adbd` over TCP (`RealAdbdIntegrationTest`, and every tab through the debug "Own stack" button), but the USB bulk-transfer adapter (`UsbPacketChannel`) and Android's USB permission prompt have never run. Still to confirm: device list and permission prompt, first-connection Allow prompt and Cancel, reconnect without a prompt, pulling the cable shows an error not a hang, leaving the hub releases the device, controlling phones on Android 8 to 11.
- Older targets: if a device rejects the handshake's checksum mode (version `0x01000000`), the known fix is to advertise `0x01000001` (`core/adb/usb/AdbMessage.kt`); not applied because not observed.
- **Connect**: Scan finding a wireless-debugging target (random port) by mDNS on a real network and on Android 13 to 16; Pair with a real code; fresh install, Scan first, then pair and connect.
- **Files**: a file over 1 GB uploads; upload into a read-only folder fails visibly; `stat -c %s` exists on the oldest target.
- **Power and Shell**: wireless debugging off and "Restart adb server" show their success message.
- **Apps**: a failed install shows `pm`'s reason.
- **Targets older than Android 17**: file listing, shell, `logcat -T 200`, mirroring.
- **Mirror**: real devices, a USB target, touch after rotating the target. If the decoder shows nothing on some device, SPS/PPS may need splitting into `csd-0`/`csd-1`; if the encoder is rejected, `max_size` may need lowering.
- **Instrumented hub walk**: never ran. With `adbTarget` it timed out because no Allow dialog appeared.
- **Reported by the owner**: a device that used to connect no longer does after updating; not reproduced. If an earlier build regenerated the key on that phone, the target must authorise or pair once more.

## USB-OTG constraints

- The controlling phone must support USB host (OTG).
- A target already in a USB debugging session with a computer cannot be shared.
- One device at a time; plugging a device in does not open the app.
- If a command stalls, the whole USB connection is dropped and re-established on the next command, which also ends anything else running over it (shell, logs, mirror).

## Open bugs (seen on the emulators)

- **1b**: one unexplained "Connection to the device was lost" on the Power tab right after leaving Logs. Six further attempts were fine. Failure causes now show on screen and in logcat (tag `AdbClient`); read that first if it recurs.
- **1c**: one unexplained stall of a large upload on the library transport (a write blocked past the 15 s watchdog). Check whether it is the emulator link or library flow control before touching the timeout.
- **1d**: over the Own stack button, a command stalled right after leaving Mirror, once. Reconnect hangs were fixed since (10 s handshake bound, reader no longer blocks on a write) but the stall did not recur in 15 rounds and the target was overloaded, so it is not confirmed fixed. Logcat tag `AdbConnection` now reports open streams and the last read time.
- **1f**: emulator-to-emulator connections drop after large transfers (`connection terminated: read failed`); the app reconnects by itself. Believed to be the emulators' networking; re-check on real devices.
- Reconnect from the banner does not yet return to the same tab (planned).

## Screen mirroring (scrcpy)

- No audio, clipboard sync, file drop or recording; video and touch only. No hardware keyboard, mouse hover or scroll wheel.
- H.264 only, at most 1280 px on the long side and 4 Mbit/s. Target needs Android 5 or newer.
- The server runs as the ADB `shell` user: secure windows and DRM content show black.
- The server version (v5.0.1) is pinned; jar and protocol code change together.
- Some vendor ROMs may refuse the server or encoder; the error text shows but size and encoder cannot be chosen yet.
- Network targets use a second ADB connection while Mirror is open.

## Other

- Monitor is screenshots in a loop, view only.
- Not built: shell history, file multi-select, APK extract, mirror audio, several device sessions, fastboot.
- CI cannot drive a physical device; the `emulator-tests` job is `continue-on-error` and not confirmed green. See [Testing](testing.md).

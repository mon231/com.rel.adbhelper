# Plan

Open work, highest priority first. An item is deleted from this file once it is done and committed.

## Must fix

1. Rotation / process death crashes the hub: `DeviceSession` is cleared on dispose, every tab does `requireNotNull(DeviceSession.client)`.
2. `AdbClient.push` boxes every byte (`data.toList()`), and uploads/installs read the whole file on the main thread: out of memory on normal-size APKs.
3. `push` / `pullTo` build `cat '$path'` without `shellQuote`: names with `'` break, hostile names run commands.
4. CI signs APKs with `signapp`'s public built-in keystore from an unpinned `pip install`.
5. `pm install` of a large APK hits the 15 s shell watchdog and reports "Install failed".
6. Failed commands are turned into empty output: a dead link shows "no apps" / blank info / no processes.

## Should fix

7. `ProcessListScreen` exists but has no tab.
8. `push` never verifies the write: "Uploaded" is shown for a read-only destination.
9. Shell tab never closes its stream or reader; `send` has no error handling.
10. Shell output list grows without limit.
11. Reboot / wireless-debugging off / adbd restart report "Command failed" because they cut the link.
12. A truncated screenshot is saved as a good PNG.
13. Symlinked directories cannot be opened; at `/` the path becomes empty.
14. No test for the destructive file commands (`rm -rf`, `mv`, `cp -r`).
15. Dead code: `ProcessRunner`, `classifyApps`, `AdbStream.readAll`, `LanScanner` `prefix` argument.

## Nice to have

16. `.` and `..` are listed as files.
17. `runShell` re-decodes the whole buffer on every chunk.
18. `thirdParty.toSet()` rebuilt per app.
19. "Recent" is sorted by text and never shrinks.

## Features

20. Logcat viewer tab: live tail, filter by level / tag / text.
21. USB-OTG transport: ADB wire protocol over `UsbManager` bulk endpoints, replacing the Connect screen stub.

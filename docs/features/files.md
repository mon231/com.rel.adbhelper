# Files

## What it does

Browses the target's filesystem from `/sdcard` with a hidden-files switch. Per entry: open (directories browse in, files show an info dialog), copy, paste, rename, delete (confirmed), download. Upload sends a file from the controlling device.

## How to use it

1. Open **Files**; tap a folder to enter. Toggle the hidden-files switch to show dot files.
2. Copy an entry, open the destination folder, tap **Paste**.
3. Upload: pick a file with the system picker; the message says "Uploaded <name>".
4. Download: the file is saved to this app's external files directory; the message shows the full path.

## How it works underneath

- Listing: `ls -la` on the directory (so symlinks list their contents), parsed by `FileListParser`.
- Copy `cp -r`, rename `mv`, delete `rm -rf`, all with shell-quoted paths.
- Upload (`AdbClient.push`): opens `exec:cat > path`, writes 256 KiB chunks straight from the input stream (not held in memory), each write under a 15 s watchdog. **Verification**: after closing, `stat -c %s` must equal the bytes sent; it is retried 5 times at 200 ms before failing with "wrote N bytes but the target has M".
- Download (`pullTo`): `stat -c %s` first, then `exec:cat path` streamed chunk by chunk to disk; the bytes received must equal the expected size or it fails.

## Limits and known issues

- No size cap in code: 32 MiB transfers pass in tests and a 28 MB upload and download with matching checksums was run on the emulator. Files over 1 GB are **unverified on real hardware**.
- Upload into a read-only folder should report a failure; unverified on real hardware.
- `stat -c %s` must exist on the target; only Android 17 was tried.
- No multi-select. `rm -rf` has no undo.
- One unexplained upload stall was seen once on the library transport (open bug 1c).

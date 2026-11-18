# Apps

## What it does

Lists installed apps with **All** / **Third-party** / **System** filters and acts on them: enable/disable, uninstall, clear data, clear cache, details (version, min/target SDK, install/update time, permissions), launch, install an APK, and "Activate (needs root)".

## How to use it

1. Open **Apps**, pick a filter, tap an app.
2. Choose an action. Disabling a system app and clearing data ("App data cannot be recovered once cleared.") ask first.
3. To install, use the install button and pick an APK with the system file picker. Progress shows "Installing <name>…", then "Installed <name>" or "Install failed: <reason>".

## How it works underneath

- List: `pm list packages -3`, `-s`, `-d`.
- Enable: `pm enable`; disable: `pm disable-user --user 0`; uninstall: `pm uninstall --user 0`; clear: `pm clear` and `pm clear --cache-only`. Package names are shell-quoted.
- Install is streamed, never held in memory: the APK is pushed (`exec:cat >`, size-verified, see [Files](files.md)) to `/data/local/tmp/adbhelper-install.apk`, then `pm install -r` runs with a 5 minute timeout, and the temp file is removed afterwards (best effort).
- Activate: `su -c pm grant <pkg> android.permission.WRITE_SECURE_SETTINGS`; it needs root and explains that when unavailable.

## Limits and known issues

- Everything runs as the ADB `shell` user, so some operations on protected packages are refused.
- A failed install showing `pm`'s own reason is implemented but unverified on real hardware.
- Only Android 17 targets tried.

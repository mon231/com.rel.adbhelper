# Power

## What it does

- **Advanced reboot**: normal, recovery, bootloader, each confirmed.
- **Display DPI**: shows the current density, applies a new one (72 to 1000, with a warning) or resets it.
- **Wireless debugging**: on, or off (confirmed, as it disconnects the session).

## How to use it

Open **Power**, choose an action, confirm. Reboot and wireless-debugging-off cut the link, so expect the Connection lost banner afterwards.

## How it works underneath

`reboot`, `reboot recovery`, `reboot bootloader`; `wm density <dpi>` / `wm density reset`; `settings put global adb_wifi_enabled 1|0`.

## Limits and known issues

- Reboot is verified on emulator. Wireless debugging off showing its success message is unverified on real hardware.
- A wrong DPI may render badly; Reset restores it.

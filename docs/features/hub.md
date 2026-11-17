# Device hub

## What it does

Shown after connecting. A header with the target address and a **Disconnect** button, a scrollable tab row (Info, Apps, Files, Shell, Processes, Logs, Power, Monitor, Mirror), and the selected tab's content. Switching tabs disposes the previous tab, which ends its streams.

## How to use it

- Tap a tab to switch. The selected tab survives rotation.
- **Disconnect** returns to Connect and releases the session (and the USB interface, if any).
- If the link drops, a **Connection lost** banner appears with the cause (up to three lines) and two buttons: **Reconnect** and **Disconnect**.

## How it works underneath

- `DeviceSession` is a singleton carrying the connected `AdbClient`; the hub detaches it when it leaves, except during rotation or dark-mode changes.
- `ConnectionHealth` never sends traffic itself. `AdbClient` reports each command and stream outcome; a failure sets the lost state and any later success clears it.
- **Reconnect** runs `echo ok` through the client, which re-establishes the transport if needed. Success clears the banner; failure updates the cause.
- Watchdogs: 15 s for commands, 2 s per chunk for raw streams. A stuck command closes the connection so the next command reconnects.

## Limits and known issues

- Reconnect from the banner does not yet promise to return you to the same tab (planned, see [Known limitations](../known-limitations.md)).
- One unexplained "Connection to the device was lost" was seen once (open bug 1b).
- The banner logic is verified in unit tests; the banner flow has not been confirmed on real hardware.

# Connect

## What it does

The first screen. Reaches a target over the network or a USB cable and opens the [device hub](hub.md).

## How to use it

**By address**

1. Type `ip:port` in **Device address** (placeholder `192.168.1.5:5555`).
2. Tap **Connect**.
3. On first connect, accept "Allow USB debugging" on the target.

**Recent**: up to 10 network targets, newest first. Tap one to reconnect, **Remove** to forget it.

**Scan**: tap **Scan**. It shows "Scanning for devices on the local network…", then a **Found devices** list ("No adb devices found" if empty). Tap a result to connect.

**Pair**: tap **Pair**, enter the pairing `ip:port` and the 6-digit code from the target's Wireless debugging screen, tap **Pair**. The dialog shows "Pairing…", then "Pairing succeeded" or "Pairing failed". If exactly one device on the network is offering pairing, the address is pre-filled. Then connect normally.

**USB-OTG**: tap **USB-OTG**.

1. The dialog lists attached devices exposing a USB debugging interface; with none it says "No USB device with debugging enabled found. Connect the target with an OTG cable and enable USB debugging."
2. Pick one. Android asks to allow access to the USB device; accept.
3. The first time, the target shows its Allow prompt. The app shows "Waiting for authorisation on the device…"; accept on the target, or tap **Cancel**.
4. Leaving the hub releases the device and its claimed interface.

Debug builds also show an **Own stack (debug)** button for testing; see [Testing](../testing.md). It is not in release builds.

## How it works underneath

- Network: `NetworkAdbTransport` uses `libadb-android` (TCP/TLS) through `AdbKeyConnectionManager`, which owns the app's RSA key and self-signed certificate. `ConnectGate` serialises connect attempts.
- Scan: `LanScanner` probes port 5555 across the local subnet and verifies each hit with a real ADB handshake. In parallel `MdnsDiscovery` (Android NSD) listens for wireless-debugging services, which use random ports; those results are listed as announced, without the handshake check. Pairing offers are found the same way.
- Pair: the library's pairing call with the code, using the app's key.
- USB: `UsbAdbTransport` speaks the ADB wire protocol itself (see [Architecture](../architecture.md)), over bulk transfers on the device's ADB interface, with the same RSA key as the network path.

## Limits and known issues

- USB-OTG: the protocol is proven against a real `adbd` over TCP, but the USB bulk transfers and Android's USB permission flow have **never run on hardware**.
- The controlling phone must support USB host (OTG). A target already in a debugging session with a computer cannot be shared. One device at a time.
- A stalled command over USB drops the whole connection, ending anything else running over it.
- Scan: no extra manifest permission was added for mDNS; confirm on Android 13 to 16. mDNS-found targets are not handshake-checked.
- Pairing with a real code has not been run (only the dialog).

## Requirements

Target: wireless debugging (or `adb tcpip`) for network; USB debugging plus an OTG cable for USB. Same network for Scan.

# Info

## What it does

Read-only device panel: model, manufacturer, serial, codename, CPU architecture and cores, Android version and SDK, security patch, battery (percentage, temperature, charging), storage used/total, screen size and density, Wi-Fi IP. A **Screenshot** button saves the target's screen.

## How to use it

Open the **Info** tab. Tap **Screenshot**; the message shows "Screenshot saved to ..." under this app's external files directory (`Screenshots`).

## How it works underneath

Parses `getprop`, `dumpsys battery`, `df`, `wm size` / `wm density` and `ip addr` (`core/device/DeviceInfoParsers`, unit-tested on captured output). Screenshot runs `screencap -p` and writes the PNG.

## Limits and known issues

Only tried against Android 17 targets; output formats of older Android versions are unverified.

## Requirements

Shell access only.

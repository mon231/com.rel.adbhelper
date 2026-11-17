# Mirror

## What it does

Live H.264 video of the target's screen with touch, Back, Home and Recents.

## How to use it

1. Open **Mirror**; the server starts on the target and video appears.
2. Tap and drag on the video; several fingers are forwarded as separate pointers.
3. Use **Back**, **Home**, **Recents** below it.
4. On failure the server's error text shows with **Try again**.
5. Leaving the tab stops the server on the target.

## How it works underneath

- Bundled [scrcpy](https://github.com/Genymobile/scrcpy) server **v5.0.1** (`assets/scrcpy-server.jar`, SHA-256 in `core/scrcpy/README.md`); the protocol is version-locked.
- `ScrcpySession` pushes the jar, starts it over a `shell:` stream with `audio=false`, `max_size=1280`, `video_bit_rate=4000000`, then opens the video and control sockets and reads device name and codec id.
- `VideoDecoder` feeds `MediaCodec` into a `SurfaceView`, recreated with the surface, requesting a key frame when it loses its place. `TouchMapping` converts view coordinates to video coordinates; `ControlMessages` encodes touch and key events. It follows target rotation.
- Network targets open a second, dedicated ADB connection (three simultaneous streams are needed).

## Limits and known issues

- Video and control only: no audio, clipboard, file drop, recording, keyboard or scroll wheel.
- H.264, 1280 px long side, 4 Mbit/s. DRM or secure-window content shows black.
- Works on the emulator (video, touch, Back, Home, server stops on leaving). Real devices, older Android, USB targets and touch after rotating the **target** are unverified on real hardware.
- Some vendor ROMs may reject the server or encoder; there is no UI to lower the size yet.
- Over USB, a stalled command drops the connection and the mirror with it.

## Requirements

Target Android 5 or newer.

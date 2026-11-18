# Testing

What is tested and how. For commands see [Contributing](contributing.md).

| Layer | What | Where |
|---|---|---|
| Unit tests | Parsers, protocol framing, view models with a fake transport, the USB stack against a scripted in-memory peer, scrcpy protocol and session, identity checks (294 tests at last count) | `app/src/test` |
| `RealAdbdIntegrationTest` | The app's own ADB stack against a real `adbd` over TCP: key authorisation, 32 MiB transfers, three simultaneous streams, reconnect, a real scrcpy session. Opt-in with `ADBHELPER_REAL_ADBD=host:port`; 11 cases pass | `app/src/test` |
| Instrumented UI tests | `AppUiTest`: Connect screen entry points, left-to-right under RTL, USB dialog without a device, unreachable address error; plus an opt-in hub walk with `adbTarget` | `app/src/androidTest` |
| Emulator CI job | `emulator-tests` in `release.yml`: runs only when a release tag `v*` is pushed; API 34 emulator, runs the integration test and the instrumented tests; `continue-on-error`, so it does not block the release | `.github/workflows/release.yml` |
| Manual | Two Android 17 emulators, every tab over the network, rotation, process death, Hebrew locale | n/a |

Honest status: on an Android 17 emulator 4 of 5 instrumented tests passed and the hub walk never ran (no Allow dialog appeared). The USB bulk-transfer adapter and Android's USB permission flow are the only untested USB code. Debug builds have an **Own stack (debug)** button that runs every tab over the USB stack through TCP; it is absent from release builds.

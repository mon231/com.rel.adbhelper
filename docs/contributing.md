# Contributing

## Ground rules

- English only — no localization.
- No monetization, no "premium" gating. Everything is free.
- No code, resources, or binaries copied from the original `didjdk/adbhelper` project — only
  genuine upstream libraries and official prebuilt binaries (AOSP `adb`/`fastboot`, Genymobile
  `scrcpy-server`), never anything extracted from the reference APK.
- Small, single-responsibility modules; short functions; names that say what they do.

## Code layout

See [Architecture](architecture.md) for the package structure and transport design.

## Testing

Pure logic (`core/*`) gets a real unit test — see existing tests under `app/src/test` for the
pattern (fixture-based parser tests, fake-transport `AdbClient` tests). UI and anything that
talks to a live device is smoke-tested by hand against a real target; note what you smoke
tested in your PR description rather than claiming automated coverage that doesn't exist.

### Exercising the USB stack without USB hardware

ADB over plain TCP is the same wire protocol as ADB over USB, so the app's own USB stack
(`core/adb/usb`) can be run against an emulator's `adbd`:

- **Own stack (debug) button**: debug builds only, next to "USB-OTG" on the Connect screen. It takes
  the `host:port` in the address field and connects through `UsbAdbTransport` over a TCP socket
  instead of the network ADB library, so every tab runs over our own stack (the session target is
  `raw:host:port`; it is not added to recents). Absent from release builds.
- **`ADBHELPER_REAL_ADBD` integration test**: `RealAdbdIntegrationTest` is skipped unless the
  environment variable or system property `ADBHELPER_REAL_ADBD=host:port` is set, e.g.
  `ADBHELPER_REAL_ADBD=127.0.0.1:5557 bash ./gradlew :app:testDebugUnitTest --tests '*RealAdbdIntegrationTest*'`.
  It uses a persistent test key in `~/.adbhelper-test/adbkey.pk8` (never in the repo), so the
  device asks for authorisation once: accept the dialog within 60 s on the first run.

### Instrumented UI tests

`app/src/androidTest` holds a small Compose UI suite (`AppUiTest`): Connect screen entry points,
layout stays left-to-right under an RTL window, USB dialog with no device, and an unreachable
address ending in an error. With an emulator or device attached:

```bash
bash ./gradlew connectedDebugAndroidTest
# plus the end-to-end hub walk (every tab, `echo hi` in Shell) against a target's adbd:
bash ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.adbTarget=127.0.0.1:5555
```

!!! warning
    `connectedAndroidTest` **uninstalls the app from the device afterwards**, which deletes the
    app's ADB key there, so the device will ask to authorise again. Do not run it on a device you
    care about; use a throwaway emulator.

The `adbTarget` test is skipped without that argument. It uses the app's own key, so the target
must trust it: tap Allow on the first connection (the test waits up to 2 minutes), or use a
target that does not require authorisation. Nothing automates this yet.

### CI emulator job

The `emulator-tests` job in `.github/workflows/build.yml` boots an API 34 `google_apis` x86_64
emulator (`android-emulator-runner`) and runs `.github/scripts/emulator-tests.sh`: it writes the
integration-test key as an `adb_keys` line (`ExportTestAdbKeyTest`), uses `adb root` to append it
to `/data/misc/adb/adb_keys` (no Allow dialog), opens adbd on TCP with `adb tcpip`, forwards it
to `127.0.0.1:5557`, then runs `RealAdbdIntegrationTest` and `connectedDebugAndroidTest`. The
`adbTarget` test stays skipped there. The job is `continue-on-error` because it has never run.

## Building docs locally

This site is built with [Material for MkDocs](https://squidfunk.github.io/mkdocs-material/):

```bash
pip install mkdocs-material
mkdocs serve
```

## Picking up a known gap

[Known limitations](known-limitations.md) lists the two biggest gaps (USB-OTG transport, screen
mirroring) along with what each would actually require. Either is a substantial, standalone
piece of work — expect to open its own design discussion before writing code.

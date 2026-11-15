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

## Building docs locally

This site is built with [Material for MkDocs](https://squidfunk.github.io/mkdocs-material/):

```bash
pip install mkdocs-material
mkdocs serve
```

## Release signing

CI signs APKs with the project's own keystore, held in GitHub Actions secrets, using the SDK's
`zipalign` and `apksigner`. The release workflow fails if any of these is missing; the build
workflow falls back to the debug-key-signed APK (pull requests from forks have no secrets).

| Secret | Value |
| --- | --- |
| `SIGNING_KEYSTORE_BASE64` | The keystore file, base64-encoded |
| `SIGNING_KEYSTORE_PASSWORD` | Keystore password |
| `SIGNING_KEY_ALIAS` | Alias of the signing key inside the keystore |
| `SIGNING_KEY_PASSWORD` | Password of that key |

Creating a keystore and encoding it:

```bash
keytool -genkeypair -v -keystore adbhelper.jks -alias adbhelper   -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 adbhelper.jks   # paste the output into SIGNING_KEYSTORE_BASE64
```

Keep the keystore and its passwords backed up somewhere private and never commit them: losing
the key means no further updates can be installed over existing installs.

APKs signed with this key **cannot be installed over** builds signed with the old signapp
built-in key (Android rejects the signature change). Anyone with such a build must uninstall
it once before installing a newer one.

## Picking up a known gap

[Known limitations](known-limitations.md) lists the two biggest gaps (USB-OTG transport, screen
mirroring) along with what each would actually require. Either is a substantial, standalone
piece of work — expect to open its own design discussion before writing code.

# Identity, layout and supported versions

## ADB identity (key handling)

The app proves itself to targets with one RSA key and a self-signed certificate in its private storage (`AdbKeyConnectionManager`, generated with BouncyCastle). Targets that chose "Always allow" recognise the app by that key, so a readable key is **never replaced**. When the identity is loaded, `identityAction` picks one of:

| Action | When | Effect |
|---|---|---|
| `KEEP` | Key usable, certificate parses, matches the key, not near expiry | Nothing changes |
| `REISSUE_CERT` | Key is fine but the certificate is missing, unreadable, mismatched or about to expire | New certificate from the **same** key; targets still trust the app |
| `REGENERATE` | Key unreadable or unusable | New key and certificate; old files are kept as `.bak`; targets must authorise again |

Files are written atomically (temp file, then rename). The same key serves network, pairing and USB. Upgrading over an older build keeps the key (verified on emulator).

## Forced left-to-right layout

`MainActivity` provides `LayoutDirection.Ltr` to the whole UI, so the app is never mirrored on right-to-left locales such as Hebrew. This is a deliberate product decision. Verified on emulator (Hebrew locale) and by an instrumented test.

## Supported Android versions

- Controlling device: **minSdk 26** (Android 8.0), **targetSdk 36**.
- Targets: Mirror needs Android 5 or newer. Hand testing was on Android 17 (API 37) only; older targets are unverified (see [Known limitations](../known-limitations.md)).
- Controlling phones on Android 8 to 11 are unverified.

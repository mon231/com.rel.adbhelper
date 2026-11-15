# scrcpy server (bundled)

`app/src/main/assets/scrcpy-server.jar` is the unmodified `scrcpy-server-v5.0.1` release asset.

| | |
|---|---|
| Version | `5.0.1` (`ScrcpyProtocol.VERSION`; the server's `BuildConfig.VERSION_NAME`, no `v` prefix) |
| Download | https://github.com/Genymobile/scrcpy/releases/download/v5.0.1/scrcpy-server-v5.0.1 |
| Size | 733930 bytes |
| SHA-256 | `764eb6f79811d5211fe9df341120882ba9994c7a61b897d7bf3fb662e53bc536` |
| Published checksum | `SHA256SUMS.txt` of the v5.0.1 release (and the GitHub asset digest) list the same value. The release notes text itself carries no checksum. |
| Licence | Apache-2.0, see `docs/third-party.md` |

Re-verify: `sha256sum app/src/main/assets/scrcpy-server.jar`.

The server refuses a client whose version string differs, so upgrading means replacing the jar,
`ScrcpyProtocol.VERSION`, this file, and re-reading the sources below at the new tag.

## Sources relied on (all at tag `v5.0.1`)

- `doc/develop.md` (connection, socket order, dummy byte, codec id, session packet, frame header)
- `server/src/main/java/com/genymobile/scrcpy/Options.java` (argument names; `scid` is parsed as hex)
- `server/src/main/java/com/genymobile/scrcpy/Server.java`
- `server/src/main/java/com/genymobile/scrcpy/device/DesktopConnection.java` (forward-tunnel accept order: video, audio, control; dummy byte; 64-byte device name)
- `server/src/main/java/com/genymobile/scrcpy/device/Streamer.java` (codec id, session packet, frame header bits)
- `server/src/main/java/com/genymobile/scrcpy/control/ControlMessage.java` and `ControlMessageReader.java` (message types and field widths)
- `server/src/main/java/com/genymobile/scrcpy/control/Controller.java` (touch handling: DOWN/MOVE/UP per pointer id)
- `server/src/main/java/com/genymobile/scrcpy/util/Binary.java` (u16 fixed-point pressure)
- `app/tests/test_control_msg_serialize.c` (byte vectors reused in tests)

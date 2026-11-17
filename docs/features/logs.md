# Logs

## What it does

Live logcat viewer, coloured by level.

## How to use it

Open **Logs**. Use **Pause** / **Resume** (lines arriving while paused are skipped), **Clear**, **Level** (minimum level, Verbose to Error) and the filter field (matches tag or message, ignoring case). The view follows the newest line while you are at the bottom.

## How it works underneath

Streams `shell:logcat -v threadtime -T 200` (last 200 lines, then follow). `LogcatParser` splits lines into level, tag and message; `filterLogLines` applies level and text. The newest 5000 lines are kept. Leaving the tab closes the stream, which ends `logcat` on the target.

## Limits and known issues

- Lines are skipped, not buffered, while paused. Only the threadtime format is parsed.
- `-T 200` on targets older than Android 17 is unverified.
- Leaving Logs for Power once showed "Connection lost" (open bug 1b, not reproduced).

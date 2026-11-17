# Shell

## What it does

A persistent interactive terminal on the target.

## How to use it

Open **Shell**, type a command, send it, and read streamed output. **Clear output** empties the scrollback. **Restart terminal** closes and reopens the session. **Restart adb server** restarts `adbd` on the target (needs root).

## How it works underneath

Opens the `shell:` service as a long-lived stream read on a background coroutine; `\r\n` line endings are normalised. Restart adb server runs `setprop ctl.restart adbd`; because adbd drops the connection, the outcome is reported as a message rather than an exit code.

## Limits and known issues

- No command history yet. Plain text only, no full terminal emulation.
- "Restart adb server" showing its success message is unverified on real hardware.

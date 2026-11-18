# Monitor

## What it does

A live, auto-refreshing view of the target's screen, view only.

## How to use it

Open **Monitor**. Use [Mirror](mirror.md) when you need touch or smooth video.

## How it works underneath

Repeats `exec:screencap -p` and decodes each PNG, pausing only 5 ms between captures. A stalled read is forced closed after 2 s.

## Limits and known issues

Low frame rate; protected content may show black. Verified on emulator only.

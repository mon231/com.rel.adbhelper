# Processes

## What it does

Lists running processes with a kill action per row.

## How to use it

Open **Processes**, use kill on a row and confirm. Killing a system process shows "This is a system process — are you sure you want to kill it?".

## How it works underneath

`ps -A` parsed by `ProcessListParser`; kill runs `kill <pid>` and reports "Failed to kill process" on failure.

## Limits and known issues

The `shell` user can only kill its own processes unless the target is rooted. Only Android 17 tried.

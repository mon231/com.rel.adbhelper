#!/usr/bin/env bash
# Runs with the emulator already booted (see the emulator-tests job in release.yml, which runs only on v* tags).
set -euxo pipefail

# google_apis images are userdebug builds, so adbd can run as root. Without root the keys file
# below is not writable.
adb root
adb wait-for-device

# Pre-authorise the integration test's key: adbd trusts every public key listed in
# /data/misc/adb/adb_keys, so no "Allow USB debugging?" dialog ever appears.
adb push test-adbkey.pub /data/local/tmp/test-adbkey.pub
adb shell 'cat /data/local/tmp/test-adbkey.pub >> /data/misc/adb/adb_keys && chmod 640 /data/misc/adb/adb_keys && rm /data/local/tmp/test-adbkey.pub'

# Make adbd also listen on TCP 5555 (it restarts, hence the waits and the reload of adb_keys).
# The integration test speaks the adb protocol over TCP, the same wire protocol as USB.
adb tcpip 5555
sleep 5 # shortcut: adbd has no "TCP ready" signal; the connect loop below is the real check
adb wait-for-device

# Reach the emulator's adbd from the host as 127.0.0.1:5557.
adb forward tcp:5557 tcp:5555
for _ in $(seq 1 30); do (echo > /dev/tcp/127.0.0.1/5557) 2>/dev/null && break; sleep 1; done

# 1) The real-adbd integration test against the emulator.
ADBHELPER_REAL_ADBD=127.0.0.1:5557 bash ./gradlew :app:testDebugUnitTest --tests '*RealAdbdIntegrationTest*' --rerun-tasks

# 2) The instrumented UI tests. The adbTarget end-to-end test stays skipped here: the app's own
# key (generated at runtime inside the app) is not pre-authorised, so it is unproven on CI.
bash ./gradlew connectedDebugAndroidTest

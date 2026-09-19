#!/usr/bin/env bash
set -euo pipefail

# API 37's emulator mapper crashes while the gesture bar samples screen colors.
# The qemu.hw.mainkeys boot property does not prevent it on this system image.
# Select and verify a real navigation overlay before launching instrumentation.
overlay=com.android.internal.systemui.navbar.threebutton
for attempt in {1..30}; do
  if adb shell cmd overlay enable-exclusive --user 0 --category "$overlay"; then
    if adb shell cmd overlay list --user 0 | tr -d '\r' | grep -Fx "[x] $overlay"; then
      echo "Three-button navigation is active for Android runtime tests."
      exit 0
    fi
  fi
  sleep 2
done

echo 'Could not activate three-button navigation on the test emulator.' >&2
exit 1

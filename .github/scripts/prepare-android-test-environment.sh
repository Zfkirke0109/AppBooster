#!/usr/bin/env bash
set -euo pipefail

# API 37 / emulator 37.1.11 aborts in mapper.ranchu when system_server
# persists Recents thumbnails. This affects the test host, not app assertions.
# AOSP TaskSnapshotController reads config_disableTaskSnapshots at startup:
# https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/wm/TaskSnapshotController.java
# Disable that unrelated OS feature only on this disposable CI emulator.
test "${GITHUB_ACTIONS:-}" = true
test "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = 1
test "$(adb shell getprop ro.build.version.sdk | tr -d '\r')" = 37
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
bash "$script_dir/prepare-android-test-navigation.sh"

adb root
adb wait-for-device
test "$(adb shell id -u | tr -d '\r')" = 0
adb shell cmd overlay fabricate --target android --name OptiDroidCiSnapshots \
  android:bool/config_disableTaskSnapshots 0x12 0x1
adb shell cmd overlay enable --user 0 com.android.shell:OptiDroidCiSnapshots
test "$(adb shell cmd overlay lookup --user 0 android android:bool/config_disableTaskSnapshots | tr -d '\r')" = true

# The snapshot controller caches its resource at system_server construction.
adb reboot
timeout 120 adb wait-for-device
ready=false
for attempt in {1..90}; do
  if [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; then
    ready=true
    break
  fi
  sleep 2
done
test "$ready" = true
adb unroot
adb wait-for-device
test "$(adb shell id -u | tr -d '\r')" = 2000
test "$(adb shell cmd overlay lookup --user 0 android android:bool/config_disableTaskSnapshots | tr -d '\r')" = true
bash "$script_dir/prepare-android-test-navigation.sh"
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell wm dismiss-keyguard
echo 'CI emulator ready: Recents snapshots disabled; app instrumentation runs with normal app permissions.'

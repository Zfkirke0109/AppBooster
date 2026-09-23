#!/usr/bin/env bash
set -euo pipefail
trap 'echo "CI emulator preparation failed at line $LINENO" >&2' ERR

# API 37 / emulator 37.1.11 aborts in mapper.ranchu while system_server
# persists Recents thumbnails. Configure the disposable test host only;
# release app code and all instrumentation assertions remain unchanged.
test "${GITHUB_ACTIONS:-}" = true
test "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = 1
test "$(adb shell getprop ro.build.version.sdk | tr -d '\r')" = 37
test "$(adb shell id -u | tr -d '\r')" = 2000
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
bash "$script_dir/prepare-android-test-navigation.sh"

# Invoke the live WindowManager switch by its runtime interface rather than
# hard-coding a Binder transaction ID. A resource overlay was lost on reboot.
# https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/wm/WindowManagerService.java
ci_classes="$(mktemp -d)"
trap 'rm -rf "$ci_classes"' EXIT
javac --release 8 -d "$ci_classes" "$script_dir/DisableCiSnapshots.java"
"$ANDROID_HOME/build-tools/37.0.0/d8" --min-api 29 \
  --lib "$ANDROID_HOME/platforms/android-37.0/android.jar" \
  --output "$ci_classes" "$ci_classes/DisableCiSnapshots.class"
adb push "$ci_classes/classes.dex" /data/local/tmp/optidroid-ci-snapshots.dex
adb shell CLASSPATH=/data/local/tmp/optidroid-ci-snapshots.dex app_process /system/bin DisableCiSnapshots
adb shell dumpsys window > "$ci_classes/window-state.txt"
grep -B 3 -A 3 'mSnapshotEnabled=' "$ci_classes/window-state.txt"
grep -F 'mSnapshotEnabled=false' "$ci_classes/window-state.txt"
adb shell rm /data/local/tmp/optidroid-ci-snapshots.dex
echo 'CI emulator ready: task snapshots disabled; normal shell and app permissions retained.'

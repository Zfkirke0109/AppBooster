# S23 Ultra Release Validation

This checklist validates the signed Galaxy OptiDroid release on a Samsung Galaxy S23 Ultra without root, Magisk, KernelSU, or system/vendor partition changes.

## Preconditions

- Device: Samsung Galaxy S23 Ultra SM-S918U1 on One UI 8.5 or newer.
- Shizuku is installed, running, and authorized for Galaxy OptiDroid.
- Wireless debugging or USB debugging is available from the development machine.
- Signed APK exists at `app/build/outputs/apk/release/app-release.apk`.

## Install And Launch

```powershell
adb devices
adb install -r app\build\outputs\apk\release\app-release.apk
adb shell monkey -p com.zfkirke0109.galaxyoptidroid 1
```

Confirm the app opens, the Shizuku setup state reaches Ready, and the bottom navigation can open Dashboard, Settings, and Samsung Survival.

## Shizuku And Shell Safety

Run these checks from the app UI:

- Dashboard: connect to Shizuku and run analysis.
- Settings: add one known heavy package, such as a browser or game package, then remove it.
- Samsung Survival: refresh and verify battery, thermal, and standby bucket values render without crashing.

No test should request root, `su`, Magisk, KernelSU, or system/vendor partition writes.

## Optimization Smoke Test

Use a single user-selected package first:

1. In Settings, add one valid heavy package.
2. Select Gaming/Heavy Apps mode.
3. Run optimization from Dashboard.
4. Confirm progress advances one package at a time.
5. Confirm a rollback candidate appears in Settings.
6. Tap Reset for that package and confirm the rollback completes.

Expected shell operations are limited to validated `cmd package compile`, `cmd package compile --reset`, `dumpsys`, and `am get-standby-bucket` commands.

## Samsung Guard Behavior

Verify guard behavior before longer runs:

- Battery below 35% pauses optimization before compile.
- Severe thermal status pauses optimization before compile.
- Restricted standby bucket is visible in Samsung Survival so the user can switch the app to Unrestricted.

## UI And Performance Pass

- Test portrait and landscape.
- Test default and large font sizes.
- Scroll Settings with many rollback candidates and heavy app targets.
- Confirm the release APK stays responsive during analysis and one-package optimization.
- If using 120 Hz tooling, watch for obvious jank while scrolling Dashboard logs and Settings rollback rows.

## Evidence To Capture

- APK signer certificate SHA-256.
- Shizuku Ready screenshot.
- Samsung Survival screenshot after refresh.
- Optimization success or pause result.
- Rollback success result.

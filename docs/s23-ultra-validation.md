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

---

# 2026-07-09/10 Wireless ADB Debug Validation (PR #4)

Results of the real-device validation run for branch
`fix/s23-full-compile-dex2oat-scan` (debug build, wireless ADB, no root,
no Knox/SELinux changes).

## Device

- Samsung Galaxy S23 Ultra SM-S918U1, Android 16, One UI 8.5
- Build `BP4A.251205.006.S918U1UES8FZF5`, security patch 2026-06-05
- Wireless ADB serials used across sessions: `192.168.1.127:41857`,
  `adb-R5CW6160LLN-Va93OQ._adb-tls-connect._tcp` (never hardcoded in source)

## Install / permissions

- Debug APK installed over wireless ADB. The previously sideloaded release
  build (1.6.0) had a different signature; it was uninstalled with the
  owner's explicit approval before the first debug install.
- AGP `connectedDebugAndroidTest` uninstalls the app after each run; it was
  reinstalled afterwards each time.
- `POST_NOTIFICATIONS` granted=true, `moe.shizuku.manager.permission.API_V23`
  granted=true (via `pm grant`); Shizuku service running.
- App launched to `MainActivity` with zero crash lines in logcat.

## One UI 8.5 compile support evidence (from the device)

- `cmd package compile --help` → `Error: Unknown option: --help` (expected;
  the app falls back to `cmd package help`).
- `cmd package help` compile section advertises compiler filters
  `speed`, `speed-profile`, `verify` ("Available options (in descending
  order)"), plus `--full  Dexopt all above. (Recommended)` and
  `-v[:LOG_TAGS]`. The `everything` filter appears nowhere in the 793-line
  help output.

## Full Compile / DEXopt All evidence

- Scan (Analyze) started from the UI chip and completed: 715 needs
  optimization / 54 optimized (769 packages).
- Full Compile run started from the UI; the app's Shizuku shell service
  logged the exact per-package cycle:
  `cmd package compile -m speed -f --full -v <package>` →
  `cmd package dump <package>` → `dumpsys package dexopt`.
- ART responded (`artd: Should recompile: force recompilation`, `dex2oat64`
  runs), and a compiled Samsung system package flipped to
  `arm64: [status=speed] [reason=cmdline]` in `cmd package dump`.
- The run survived backgrounding via the WorkManager foreground service
  (ongoing notification on the `optimization` channel).
- Honest counting held after stop: 31 verified optimized out of 35
  processed; analysis card stayed consistent (684 remaining + 85 optimized
  = 769).

## Stop/Cancel defect found on device and its fix

- Old defect: tapping Stop during a Full Compile run ended with an
  "Optimization failed" card instead of "Canceled".
- Root cause (two parts):
  1. `StopOptimizationUseCase` / `StopAnalysisUseCase` /
     `OptimizationWorkerStopReceiver` cancelled WorkManager *before* the
     repository-side cancel, so the worker could die before the cancel flag
     was recorded.
  2. `AdbRepositoryImpl` wraps runs in `runCatching`, which swallows the
     `CancellationException` thrown out of the in-flight shell command when
     WorkManager cancels the worker, and its failure handler mapped it to
     `OptimizationResult.Failed` — overwriting the `Canceled` state.
- Fix: repository-side cancellation now happens first everywhere (flag is
  set even when the visible running/scanning state has already shifted),
  and the run/scan failure handlers treat `CancellationException` (or any
  error racing a requested cancel) as `Canceled` — never `Failed`; a
  cancelled shell command is rethrown instead of being counted as a failed
  package, and a cancelled scan is not logged as a failed scan.
- Unit tests cover: repository-first ordering in both stop use cases, the
  flag path, the thrown-`CancellationException` path, the
  `Result.failure(CancellationException)` path (no `markFailed`), and the
  cancelled-scan path (no ERROR log, scan not finalised as successful).

## Test results (this continuation)

- Focused: `StopOptimizationUseCaseTest`, `StopAnalysisUseCaseTest`,
  `AdbRepositoryImplTest`, `OptimizationAnalysisTest` — BUILD SUCCESSFUL.
- Full `:app:testDebugUnitTest`, `:app:assembleDebug`,
  `:app:connectedDebugAndroidTest`, `runAllTests` — see PR #4 checklist for
  the final pass/fail state recorded at commit time.

## Remaining manual recheck

- After installing the fixed build: start a run, tap Stop, and confirm the
  result card shows **Canceled** (not "Optimization failed"), the foreground
  notification disappears, and no second compile starts.
- **Done 2026-07-10 (early session):** Stop produced "Optimization canceled —
  14 of 667 apps optimized before stopping"; no further compile commands; the
  foreground service terminated.

---

# 2026-07-10 Full to-completion marathon (PR #4, final validation)

Run on the final PR build (stop/cancel fix + new launcher icon + string
fixes), Full Compile / DEXopt All across the whole device.

## Timeline and resilience

- 02:15 — run started from the UI (638 packages needing optimization after
  earlier partial runs; fresh scan inside the run).
- ~02:54 — the marathon force-compiled `com.sec.android.app.launcher`; in the
  fallout the app's own process was killed ("Force removing ActivityRecord",
  then a "set debug app" force-stop killed the first retry).
- 02:54:48 and 02:55:05 — **WorkManager automatically restarted the worker**;
  the surviving attempt re-scanned (warm caches, ~1 min) and continued with
  the remaining 421 packages. Every retry makes forward progress, so the
  marathon converges rather than looping.
- 03:29 — run completed. Total wall time ≈ 74 minutes for ~700 packages.

## Final result (honest reporting at full scale)

- Result card: **"Finished with issues — 381 verified, 0 failed/refused,
  40 unverified"** with totals 728 Optimized / 40 Unverified.
- The 40 packages whose post-compile ART evidence was unclear are counted
  separately and the card is error-styled — the app refuses to claim clean
  success, exactly per the truth rules.
- Thermal status stayed 0 (none) throughout (battery peaked ≈ 37.6 °C early,
  21.1 °C at the 03:07 check; SoC 42.2 °C); battery never approached the
  35% guard (55-74% band, charger attached part of the run).

## Cancel-during-scan (item 22b) — status

- Unit-tested both ways: the user-flag path and the
  worker-`CancellationException` path (no failure log, scan not finalised
  as successful, `isScanning` cleared).
- Live on-device cancellation of a scan is no longer practical on this
  hardware state: after the marathon, a full 768-package scan completes in
  ~8 seconds (warm dexopt caches) — faster than the remote screenshot+tap
  round-trip. Two automation attempts raced completion and instead hit the
  Play button that replaces Stop at the same coordinates (see UX note).
  The scan stop flow shares the repository-first cancel plumbing that WAS
  live-verified on the optimize path ("Optimization canceled" card).

## UX follow-up noted (out of scope for PR #4)

- The hero card swaps the red Stop for the pink Play button at the same
  position the instant a scan completes; a tap aimed at Stop can start an
  optimization run. Suggested fix: debounce the control for ~1s after a
  phase transition. Tracked as a follow-up task.

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

---

# 2026-07-16 Runtime Telemetry Validation (1.7.0)

Validated the uncommitted runtime-telemetry implementation on the locked,
non-root Samsung Galaxy S23 Ultra over wireless ADB. No direct `dex2oat`,
profile-directory access, CPU controls, system partition changes, or
`everything` commands were used.

## Device and app identity

- ADB serial alias: `adb-R5CW6160LLN-Va93OQ._adb-tls-connect._tcp`
- Device: Samsung `SM-S918U1`, Android 16, SDK 36
- Galaxy OptiDroid: `1.7.0` / `10700`
- Shizuku service was running; notification and Shizuku API permissions were
  granted before the run.

## One-package telemetry run

- Run ID: `1784268919835`
- Mode: `HEAVY_APPS_SPEED`
- Target: `com.deniscerri.ytdl`
- Recorded command: `cmd package compile -m speed -f com.deniscerri.ytdl`
- Exit code/stdout: `0` / `Success`
- Command duration: `5947 ms`
- Verification: `package-manager-resolver`
- Compiler filter: `speed-profile` before, `speed` after
- Terminal result: `COMPLETED`; WorkManager `WorkSpec.state = 2` (succeeded)
- Counters: 1 targeted, 1 processed, 1 verified optimized, 0 failed/refused,
  0 unverified, 0 canceled

This selected-app run used normal dexopt scope and therefore did not include
`--full`. It does not replace the earlier all-app Full Compile / DEXopt All
evidence above, which captured `cmd package compile -m speed -f --full -v
<package>` on the same Android build.

## Storage and export evidence

- Run-level available storage: `211273121792` bytes before and
  `211251150848` bytes after.
- Step-level available storage: `211272798208` bytes before and
  `211253121024` bytes after.
- Reserve policy recorded: `5368709120` bytes (5 GiB).
- Thread policy recorded: `package-manager-managed`.
- Export URI: `content://media/external/downloads/275992`.
- Export file:
  `Download/Galaxy OptiDroid/Telemetry/galaxy-optidroid-run-1784268919835-1784268933736.json`
- Room and JSON counters, command metadata, storage snapshots, compiler
  filters, duration, and verification source matched exactly.
- The JSON labels storage values as device-volume observations, not
  per-package DEX/VDEX/ART sizes.

## Live ART evidence

After the run, `cmd package dump com.deniscerri.ytdl` reported:

```text
Dexopt state:
  arm64: [status=speed] [reason=cmdline] [primary-abi]
```

No optimization foreground service remained active after completion. Stop and
cancel were not repeated during this one-package telemetry run; the earlier
2026-07-10 live cancellation result remains documented above, and the current
branch's cancellation terminal-state coverage is part of the final unit-test
gate.

---

# 2026-07-17 Full Manual Runtime Run and Binder Finding

The owner manually launched the installed `1.7.0` (`10700`) debug build and ran
Full DEXtoOAT Speed to completion while full-system Logcat capture was active.
The run used only the Shizuku UserService and allowlisted package-manager
commands.

## Terminal run evidence

- Run ID: `1784272575160`
- Mode: `FULL_DEX2OAT_SPEED`
- Requested filter: `speed`; normal dexopt scope (`fullDexoptScope=false`)
- First command:
  `cmd package compile -m speed -f com.samsung.android.engineapp.camerashift`
- Last command: `cmd package compile -m speed -f com.samsung.android.gru`
- 768 targeted; 766 compile steps; 681 verified optimized; 2 already matching;
  0 failed/refused; 85 unverified; 0 canceled
- All 766 issued compile commands returned exit code 0.
- Room terminal state: `COMPLETED_WITH_ISSUES`.
- WorkManager terminal log: `Worker result SUCCESS` for `OptimizationWorker`.
- No `FATAL EXCEPTION`, app-process crash, or app ANR was present in the
  capture.

The result is intentionally not reported as clean success. The 85 packages
whose post-run evidence could not be proven remain `UNVERIFIED`, even though
their compile commands exited successfully.

## Telemetry agreement

- JSON export URI: `content://media/external/downloads/276108`
- Export file:
  `Download/Galaxy OptiDroid/Telemetry/galaxy-optidroid-run-1784272575160-1784282844599.json`
- MediaStore row: 753614 bytes and `is_pending=0`.
- JSON schema version: 1; 766 steps (681 `SUCCEEDED`, 85 `UNVERIFIED`).
- JSON and Room run counters matched exactly.
- Available device-volume storage was `207730642944` bytes before and
  `181459001344` bytes after. This is a device-volume observation, not a claim
  that the roughly 24.47 GiB delta is package-specific DEX/VDEX/ART usage.
- Reserve: 5 GiB; thread policy: `package-manager-managed`.

## Binder defect found

Post-compile `cmd package dump <package>` output reached 3.7-4.6 MB. Returning
that text through `IShellService.executeCommand()` exceeded Binder transaction
capacity and produced repeated `FAILED BINDER TRANSACTION` /
`DeadObjectException` failures. The worker recovered and continued, but those
verification failures contributed to the unverified count and repeatedly
rebound the UserService. The once-per-run `dumpsys package dexopt` output was
measured separately at 366144 characters (about 732 KB as an AIDL UTF-16
reply).

The branch now filters per-package dump output inside the UserService to retain
only ART/compiler, timestamp, and overlay evidence; caps stdout/stderr before
crossing Binder; and calls the global resolver only when direct package or
verbose evidence is unavailable. The S23's complete current global dexopt dump
fits below the selected cap. Focused and full unit tests pass; a post-fix device
smoke test remains part of the final instrumented/install gate.

## Post-fix build and signing gate

The final Binder-safe source passed `runUnitTests`, `:app:assembleDebug`, and
`:app:assembleRelease` on 2026-07-17. The release APK reports application ID
`com.zfkirke0109.galaxyoptidroid`, version name `1.7.0`, and version code
`10700`. `apksigner verify --verbose --print-certs` verified APK Signature
Scheme v2 with one RSA-4096 signer (`CN=Zfkirke0109`) and certificate SHA-256
`bb93cab28f64a1cd14c92f771a91d4e000498448723cff3c6571a48cc6715723`.

The remaining gate is `runInstrumentedTests` and a short post-fix device smoke
test confirming bounded Shizuku replies no longer cause Binder transaction
failures. The earlier pre-fix full-run counters remain valid historical
evidence and are intentionally not rewritten as post-fix results.

## Connected tests and reinstall

`runInstrumentedTests` completed against the physical `SM-S918U1` over the
stable wireless ADB serial
`adb-R5CW6160LLN-Va93OQ._adb-tls-connect._tcp`: 3 tests finished with 0
failures and Gradle reported `BUILD SUCCESSFUL in 4m 1s`. This exercises the
Room 1-to-2 migration, MediaStore telemetry export, and existing application-ID
instrumentation coverage on Android 16.

Android Gradle Plugin removed the tested app when the connected suite ended.
The final debug APK was then reinstalled successfully without launching it.
Package Manager reports version `1.7.0` (`10700`), notification permission
granted, and `moe.shizuku.manager.permission.API_V23` granted. PR #5 CI also
passed the signing-secret scan and unit-test jobs; release jobs correctly
skipped on the pull-request event.

Only a short manual post-fix Binder smoke remains: run one selected non-system
package in Gaming / Heavy Apps and confirm that package verification finishes
without `FAILED BINDER TRANSACTION` or `DeadObjectException`. An all-device
compile must not be repeated for this check.

---

# 2026-07-21 Upstream 1.7.0 And Runtime Hardening Gate

## Upstream release comparison

- Latest upstream release: `v1.7.0-10700` (`c963005`), published 2026-07-20.
- The exact upstream tag diff from `v1.6.1-10601` changes 13 files: versioning,
  app version display, activity-feed layout/autoscroll, and release-signing
  workflow behavior. Shizuku app visibility and binder-first handling are from
  the earlier `v1.6.0-10600` to `v1.6.1-10601` delta and are also incorporated.
- Galaxy OptiDroid remains `1.7.0 / 10700` and now incorporates all applicable
  app behavior from that release.
- The fork intentionally does not adopt upstream's `PS_RELEASE_*` secrets.
  Its existing five-secret shared-signing contract additionally checks the
  expected SHA-256 certificate and is therefore stricter.

## Source fixes validated

- Shizuku uses a sticky binder listener and binder-first state detection.
- Terminal Room runs are excluded from resumable-step lookup.
- Resume clears stale export URI/error/timestamp values.
- Retention pruning runs for terminal export success and failure paths.
- Analysis notifications are deduplicated and rate-limited to one update per
  second, with terminal updates delivered immediately.
- The activity feed follows the newest retained entry instead of using an
  out-of-range index after 50 entries; app label/icon lookup runs on
  `Dispatchers.IO`.
- Settings displays BuildConfig version name/code and links to the fork.

## Local validation

- `runUnitTests`: 258 tests, 0 failures, `BUILD SUCCESSFUL in 3m 34s`.
- `:app:lintDebug :app:assembleDebug :app:compileDebugAndroidTestKotlin`:
  `BUILD SUCCESSFUL in 6m 1s`.
- `:app:assembleRelease :app:bundleRelease`: `BUILD SUCCESSFUL in 16m 33s`.
  R8, resource shrinking, lint-vital, APK packaging, and AAB signing completed.
- `tasks --all`: `BUILD SUCCESSFUL in 1m 10s`; the root convenience tasks
  `runUnitTests`, `runInstrumentedTests`, and `runAllTests` are present.
- Debug `output-metadata.json`: package
  `com.zfkirke0109.galaxyoptidroid`, version name `1.7.0`, version code `10700`.
- Release `output-metadata.json` reports the same package and version. The APK
  verifies with APK Signature Scheme v2 and the AAB certificate verifies with
  `keytool`; both use RSA-4096 signer SHA-256 `bb93...5723`.
- PR #5 checks for source checkpoint `7f1c44b`: signing-secret scan passed and
  unit tests passed. Release and publish jobs correctly skipped for the PR
  event.
- Installation and connected tests were not repeated because the installed,
  historical, debug, and shared release signers are not update-compatible.

## Release blocker

The historical Galaxy OptiDroid release certificate SHA-256 ends in `5723`;
the shared CI certificate SHA-256 ends in `7219`. A same-package/different-
signer result is an update-continuity failure. Do not uninstall the current app,
install over it, merge, tag, or publish 1.7.0 until the owner chooses an explicit
signing continuity policy.

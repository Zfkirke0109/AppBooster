# CODEX_HANDOFF.md

Continuation record for the Codex 5.5 → Claude Fable 5 handoff on the
S23 Ultra full-compile / scan-fix branch.

## Workspace

- Local path: `C:\Users\zachk\OneDrive\Documents\OptiDroid Galaxy`
- Branch: `fix/s23-full-compile-dex2oat-scan` (tracks `fork/master`, fork = `Zfkirke0109/AppBooster`)
- Upstream: `origin` = `androidexpert35/AppBooster`

## Codex WIP visibility and preservation

- Codex's work was already committed before this session as
  `787feed` — "Checkpoint: preserve Codex WIP after usage limit during Gradle"
  (29 source/test files, +2397/−145, including `CODEX_WIP_BEFORE_FABLE.patch`).
- The working tree was clean on arrival; nothing uncommitted needed rescuing.
- Local (untracked/ignored) safety files still present:
  `CODEX_WIP_BEFORE_FABLE.stat.txt`, `CODEX_WIP_BEFORE_FABLE.status.txt`.
  The `.patch` is tracked inside `787feed`.

## Commits made in this session

- `3addba2` — Checkpoint: repair stale tests for mode-key persistence and honest completion
  - Codex's intentional production changes (enum mode key persisted instead of raw
    compiler filter; runs with failed/unverified packages finish as
    `CompletedWithIssues`; stricter `allOptimized`) had left 3 pre-existing tests
    encoding the old behavior. The tests were updated, not the production code.
- `572651f` — Align Full Compile mode with One UI 8.5 and separate compile scopes
  - `ADVANCED_FULL_COMPILE` is now **Full Compile / DEXopt All**: requests `speed`
    with `--full` (was `everything`), keeps the runtime `--full` support check,
    and is enabled in the settings selector (was permanently disabled).
  - `FULL_DEX2OAT_SPEED` and `HEAVY_APPS_SPEED` no longer pass `--full`
    (normal compile scope; they differ only in target scope).
  - `everything` survives only as historical/diagnostic handling (parser detection,
    legacy stored-value migration, verification acceptance).
  - Default + Italian mode strings updated to the real commands; orphaned
    two-mode-era Italian keys (`settings_opt_full_*`,
    `dashboard_ready_full_optimization_*`) renamed to current keys — stale
    resource warnings resolved.
  - New tests: mode mapping per spec, stored-value migration/visible failure,
    plain `speed` command generation, and repository tests that Full Compile adds
    `--full` via the `cmd package help` fallback (`compile --help` fails on this
    build), Full DEXtoOAT compiles all eligible packages without `--full`, and
    Gaming / Heavy compiles only the selected packages.

## Mode matrix (verified by AppOptimizationTypeTest + AdbRepositoryImplTest)

| UI name | Filter | Target scope | Compile scope | Command |
| --- | --- | --- | --- | --- |
| Speed Profile | `speed-profile` | all eligible | normal | `cmd package compile -m speed-profile -f <pkg>` |
| Full DEXtoOAT Speed | `speed` | all eligible | normal | `cmd package compile -m speed -f <pkg>` |
| Full Compile / DEXopt All | `speed` | all eligible | full dexopt | `cmd package compile -m speed -f --full <pkg>` |
| Gaming / Heavy Apps | `speed` | selected heavy apps | normal | `cmd package compile -m speed -f <pkg>` |

## Tests / builds actually run in this session

- `.\gradlew.bat :app:testDebugUnitTest --no-daemon --max-workers=1 --stacktrace`
  - First run (Codex resume point): **213 tests, 3 failed** (the stale tests above).
  - After repairs + mode alignment: **220 tests, 0 failed — BUILD SUCCESSFUL**.
- `.\gradlew.bat :app:compileDebugKotlin :app:assembleDebug --no-daemon --max-workers=1 --stacktrace`
  - See "Validation status" below for the result recorded at handoff time.

## Scan / analyze flow (Bug 1) — verified wired, not re-verified on device

`DashBoardScreen` → `MainUiEvent.OnAnalyzeAppsClicked` → `MainViewModel.triggerAnalysis()`
→ `StartAnalysisUseCase(mode)` → `AnalysisWorker` → `AdbRepositoryImpl.performAnalysisScan`
→ `optimizationAnalysis` StateFlow → hero card SCANNING phase with current package,
counts, cancel (`OnStopAnalysisClicked`), and failure snackbar.
Unit tests cover the event dispatch and the failure path.

## Honest reporting (Bug 3) — verified

`OptimizationProgress` carries `processedCount / optimizedSucceededCount /
alreadyOptimizedCount / skippedNoProfileCount / failedOrRefusedCount /
unverifiedCount / totalCount`. Verification uses `--full -v` output,
`cmd package dump`, and `CompilationInfoResolver` evidence; unclear packages are
marked UNVERIFIED. Any failed/unverified > 0 → `CompletedWithIssues` (error-styled
result card with separated counts). The result card reads
`optimizedSucceededCount`, never the attempted count.

## Remaining work (must be done on the phone)

- Install the debug APK on the S23 Ultra (SM-S918U1, One UI 8.5 / Android 16).
- Verify Scan starts from the UI and shows progress/counts.
- Verify `cmd package help` on-device shows `--full` and the app's support log
  lists speed/speed-profile supported, everything unsupported.
- Run Full Compile / DEXopt All and confirm the issued command is
  `cmd package compile -m speed -f --full <package>` and that Samsung/Knox
  refusals are reported as failed/refused, not optimized.
- Only after device validation: mark the PR ready for review.

## Next command

```powershell
cd "C:\Users\zachk\OneDrive\Documents\OptiDroid Galaxy"
.\gradlew.bat :app:assembleDebug --no-daemon --max-workers=1
# then install app\build\outputs\apk\debug\app-debug.apk on the S23 Ultra
```

---

# Fable continuation after Codex 5.6 Sol usage limit (2026-07-10)

Fable resumed with Codex's Stop/Cancel work sitting uncommitted in the tree
(preserved to `FABLE_RESUME_AFTER_CODEX_USAGE_LIMIT.patch` before any edit).

## Files inspected (all of `git diff --name-only`)

- `ExampleInstrumentedTest.kt` — kept: asserts `BuildConfig.APPLICATION_ID`
  instead of the hardcoded namespace (fixes the only connected-test failure).
- `AdbRepositoryImpl.kt` — Codex moved the cancel flags before the
  early-returns in `cancelOptimization()`/`cancelAnalysis()`; kept.
- `StopOptimizationUseCase.kt` / `StopAnalysisUseCase.kt` — repository-first
  cancel ordering; kept.
- `OptimizationWorkerStopReceiver.kt` — repository cancel before
  `cancelWorkById`; kept.
- `AdbRepositoryImplTest.kt` — Codex's `stubPackageDump` helper, flag-path
  cancel test, and scan-cancel test; kept.
- `OptimizationAnalysisTest.kt` — failed/unverified `allOptimized` cases; kept.
- `StopOptimizationUseCaseTest.kt` / new `StopAnalysisUseCaseTest.kt` —
  order-asserting stop tests; kept.

## Files changed by Fable on top of Codex

- `AdbRepositoryImpl.kt` — the missing half of the device-observed bug:
  `executeOptimizationCommand`'s failure handler now ends the run as
  `Canceled` (never `Failed`) when the throwable is a `CancellationException`
  or a cancel was requested; a cancelled shell command surfacing as
  `Result.failure(CancellationException)` is rethrown instead of counted as
  a failed package; `analyzeOptimizationStatus` no longer logs
  `ANALYSIS_FAILED` for a cancelled scan (returns `ADB_ANALYSIS_CANCELLED`).
- `AdbRepositoryImplTest.kt` — three new tests for the exception paths.
- `docs/s23-ultra-validation.md` — appended the dated device-validation
  results section.

## Status at handoff

- Focused unit tests (Stop*, AdbRepositoryImpl, OptimizationAnalysis): green.
- Full unit suite / assemble / connected / runAllTests: recorded in the
  final session report and PR #4 checklist.
- Remaining manual recheck: install fixed build, Stop a run, expect the
  Canceled card (not "Optimization failed").

---

# Final session addendum (2026-07-10, later)

- Stop recheck passed live: "Optimization canceled — 14 of 667 apps
  optimized before stopping".
- New launcher icon (galaxy squircle artwork) + two cosmetic string fixes
  (35%% double-percent, heavy-targets subtitle) committed as `0ad0f46`,
  verified on device.
- Full to-completion Full Compile marathon completed (~74 min, ~700
  packages) on the final build: "Finished with issues — 381 verified,
  0 failed/refused, 40 unverified" — honest error-styled card at full
  scale. Survived a mid-run app-process death via two automatic
  WorkManager worker restarts. Details in docs/s23-ultra-validation.md.
- Cancel-during-scan: unit-tested (flag + exception paths); live exercise
  impossible post-marathon (warm-cache scans finish in ~8 s). Shared cancel
  plumbing live-verified on the optimize path.
- UX follow-up flagged (not in PR #4): hero card swaps Stop→Play at the
  same position when a scan completes; debounce suggested.

---

# Runtime telemetry continuation (2026-07-16)

## Current state

- Branch: `codex/runtime-telemetry`
- Version checkpoint: `8cbd585 chore: bump Galaxy OptiDroid to 1.7.0`
- Telemetry/Binder checkpoint: `173d184 feat: add Knox-safe runtime optimization telemetry`
- Remote: pushed to `fork/codex/runtime-telemetry`; draft PR
  `https://github.com/Zfkirke0109/AppBooster/pull/5`.
- Base: created from `fork/master`; initial upstream comparison was
  `origin/master...HEAD = 0 30`.
- Runtime telemetry source, tests, Room schema 2, Binder reply bounds, and
  validation documentation are committed and recoverable from the fork.
- Preserve and never stage: `DEVICE_VALIDATION_BEFORE_CODEX.patch`,
  `FABLE_RESUME_AFTER_CODEX_USAGE_LIMIT.patch`, and `validation/`.

## Device-backed telemetry result

The owner completed a one-package Gaming / Heavy Apps compile on the S23 Ultra.
Read-only extraction of the app-owned Room and WorkManager databases plus the
automatic JSON export confirmed:

- Run `1784268919835`, package `com.deniscerri.ytdl`
- Command `cmd package compile -m speed -f com.deniscerri.ytdl`
- Exit 0, duration 5947 ms, `speed-profile` -> `speed`
- Room status `COMPLETED`; WorkManager status `SUCCEEDED`
- 1 targeted, 1 processed, 1 verified optimized; no failures, unverified, or
  canceled packages
- `cmd package dump` evidence: `status=speed`, `reason=cmdline`
- 5 GiB reserve and before/after device-volume storage snapshots recorded
- JSON auto-export succeeded at
  `Download/Galaxy OptiDroid/Telemetry/galaxy-optidroid-run-1784268919835-1784268933736.json`
- Room and JSON counters and step metadata matched exactly

This selected-app run intentionally used normal dexopt scope. The earlier PR #4
validation remains the evidence for the runtime-advertised all-app `--full`
command. No second compile should be started merely to repeat this telemetry
proof.

## Full manual run and follow-up fix

The owner later completed Full DEXtoOAT Speed across the device under a full
Logcat capture:

- Run `1784272575160`, mode `FULL_DEX2OAT_SPEED`, normal dexopt scope
- 768 targeted; 766 commands at exit 0; 681 verified; 2 already matching;
  0 failed/refused; 85 unverified
- Room/JSON terminal result `COMPLETED_WITH_ISSUES`; WorkManager `SUCCESS`
- Final JSON: 753614 bytes, MediaStore `is_pending=0`, Room counters matched
- No app crash or ANR

Evidence-backed defect: `cmd package dump` replies of 3.7-4.6 MB overflowed
Binder and caused `DeadObjectException` plus repeated Shizuku UserService
rebinds. The fix now filters per-package evidence and caps UserService replies,
while preserving the measured 366144-character global dexopt cache. Repository
verification also skips the global resolver when direct package/verbose
evidence already proves the result.

Post-fix local verification completed on 2026-07-17:

- `.\gradlew.bat runUnitTests --no-daemon --max-workers=1 --stacktrace`:
  **BUILD SUCCESSFUL**.
- `.\gradlew.bat :app:assembleDebug --no-daemon --max-workers=1 --stacktrace`:
  **BUILD SUCCESSFUL**.
- `.\gradlew.bat :app:assembleRelease --no-daemon --max-workers=1 --stacktrace`:
  **BUILD SUCCESSFUL**.
- Release APK identity: `com.zfkirke0109.galaxyoptidroid`, version `1.7.0`
  (`10700`).
- `apksigner verify --verbose --print-certs`: v2 signature verified with one
  RSA-4096 signer, `CN=Zfkirke0109`; certificate SHA-256
  `bb93cab28f64a1cd14c92f771a91d4e000498448723cff3c6571a48cc6715723`.
- Diff/secret audit: clean; local keystore extensions and Gradle signing files
  remain ignored, and the tracked root `gradle.properties` is secret-free.

Final connected-test validation completed on the physical S23 Ultra over the
stable wireless ADB serial
`adb-R5CW6160LLN-Va93OQ._adb-tls-connect._tcp`:

- `.\gradlew.bat runInstrumentedTests --no-daemon --max-workers=1 --stacktrace`:
  **3 tests, 0 failures; BUILD SUCCESSFUL in 4m 1s**.
- The connected-test runner removed the app after testing. The final debug APK
  was reinstalled successfully, reports `1.7.0` (`10700`), and has notification
  and Shizuku permissions granted.
- The app remains stopped/not launched so the owner can perform the requested
  manual post-fix runtime check.
- PR #5 CI passed both `Signing secret scan` and `Unit tests`; signed-release
  and publication jobs correctly skipped for the pull-request event.

## Exact next command

```powershell
cd "C:\Users\zachk\OneDrive\Documents\OptiDroid Galaxy"
$serial = 'adb-R5CW6160LLN-Va93OQ._adb-tls-connect._tcp'
adb -s $serial logcat -c
adb -s $serial logcat -v time | Tee-Object -FilePath validation\post_binder_fix_logcat.txt
```

With that capture running, the owner should manually launch Galaxy OptiDroid
and run Gaming / Heavy Apps for one selected, non-system package. Confirm the
compile and verification complete without `FAILED BINDER TRANSACTION` or
`DeadObjectException`; do not repeat an all-device compile. Keep the PR draft
until that result is recorded. Do not tag or publish 1.7.0 until the PR is
merged and `fork/master` CI is green.

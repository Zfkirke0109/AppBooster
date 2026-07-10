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

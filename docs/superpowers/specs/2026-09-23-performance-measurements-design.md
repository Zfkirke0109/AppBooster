# 1.7.4: reliability and before/after dex2oat measurement

User instruction: continue the proposed reliability work and add true before/after performance measurements with explanations. Existing captures prove compiler outcomes, not speed gains.

## Selected design

Add a Performance tab with an explicit, three-stage flow for one user-selected launcher app: capture Before, compile that app with `speed --full` (runtime capability checked, no forced reset), capture After. Each capture launches that exact activity five times with `am start -S -W`, waits three seconds between samples, and retains all five successful cold-launch samples. Force-stop/relaunch is disclosed before the user starts. No data/cache clearing, synthetic score, or system-app mass-launching. Shell commands remain typed and allowlisted.

Alternatives considered: automatically launch all apps around a full run is disruptive and poorly controlled; external Macrobenchmark/Perfetto gives richer traces but requires a separate harness. The selected in-app shell measurement gives directly observed startup timing on this user's device. It is a sequential observation, not proof that compilation alone caused a difference.

Show median/range of Android `TotalTime` in milliseconds and signed change from baseline; retain ThisTime/WaitTime if supplied. Cold means process-cold: filesystem caches and ART/JIT profiles are not reset. First frame is not time until network content or the entire UI becomes usable. Reject timeout/hot/warm/wrong-package/zero/malformed output. Five complete samples per phase are required. Display variability and environmental warnings; suppress percentage comparison when identity/build/runtime changes. Record battery temperature, charge state, thermal status, power saver, times and version identity as context rather than measured battery savings.

Compile evidence is separate: command, elapsed time, exit code, actual ART classification/filter and artifact sizes. No fabricated filter on missing output. Failed compilation prevents after comparison. Same-filter or adjusted outcomes stay visible even if measured times differ.

Run via foreground WorkManager so opening the measured app is supported. Persist the session and samples atomically, export JSON via Android document picker, preserve baseline if a later operation fails. Cancel stops further samples after the in-flight shell call returns; do not promise native process cancellation. Interrupted phases never become complete results. Lock whole analysis/compile/rollback/measurement operations against overlap, in addition to existing per-command serialization. Use bounded timeouts for measurement commands.

## Reliability work

Use explicit successful/already-matching counts for all dashboard states, retain non-applicable/adjusted/unavailable categories, show neutral completion wording for OS-adjusted-only outcomes, and monotonic current-package elapsed time. Pause after Shizuku connection loss. Freshly validate resumed plans or safely rebuild them; do not permanently cache full-scope OS-adjusted outcomes.

## Validation and limits

Regression tests for command allowlisting, strict output parsing, medians/percentage signs, incomplete/identity-mismatched comparisons, concurrent-operation rejection and disconnect recovery. Android 17 instrumentation verifies parsers, persistence and measurement UI. Existing complete host suite/lint/build and signed release gates remain required. Physical Samsung launch-time values must be collected by the installed update; existing logs cannot supply a missing baseline. No battery, FPS, CPU or whole-device speed benefit is claimed from startup timing.

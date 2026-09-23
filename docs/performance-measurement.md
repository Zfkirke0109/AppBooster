# Performance measurement in Galaxy OptiDroid 1.7.4

## What dex2oat does

ART uses dex2oat to compile DEX bytecode ahead of time. Compiler-filter verification reports what ART produced. It does not by itself establish faster launches, better frame rates, reduced RAM use or battery savings.

The Performance tab measures one selected, user-installed launcher app on the primary Android profile. It never manufactures an earlier baseline from old optimization logs.

## Use the flow

1. Open the test app once manually, finish onboarding and sign-in, then return to OptiDroid. Save any work: measurement closes and reopens that app.
2. Select the app in Performance. Export any older session before replacing it.
3. Tap **Measure Before** and confirm five launches. Keep the phone unlocked and avoid interacting while the foreground notification runs. Return to OptiDroid after completion.
4. Tap **Compile selected app**. The command requests `speed --full`, without `-f` or clearing data/profiles. Unsupported command capabilities stop this step rather than silently changing scope. The compiler result remains visible separately.
5. Let the phone cool and reproduce the same charging/power-saving/network conditions. Tap **Measure After**.
6. Inspect both phases and export the measurement JSON. No whole-device compile is needed for this experiment.

## Metrics

| Measurement | Definition | Interpretation |
| --- | --- | --- |
| Launch median (ms) | Middle of five successful Android `am start -S -W` TotalTime readings | Lower is a shorter launch to initial display; splash screens can be the initial display |
| Launch range and samples (ms) | Minimum, maximum and all five readings | Shows variability; slow outliers remain included |
| Observed change (%) | 100 × (before median − after median) / before median | Positive is shorter, negative is longer; only shown when identity/condition checks pass |
| Compilation duration (s) | Monotonic elapsed time around the selected compile command | Optimizer cost, not app speed |
| ART sizes (MiB) | Verbose ART sizeBeforeBytes and sizeBytes, aggregated by the existing parser | Compiler artifact storage, not total device free space |
| Battery temperature, thermal status, power and charging state | Captured around launches | Test context; neither battery savings nor CPU temperature |

Five launches are a small diagnostic sample. Before/after order can warm filesystem caches and profiles; the protocol does not reset them. `-S` makes the application process cold, not the entire device. Network data, app state and unrelated background work can still affect results. Range overlap is flagged; non-overlap does not prove statistical significance or dex2oat-only causation. Apps with redirects to another package, unsupported output or warm launches are excluded instead of assigned a zero time.

Compiler adjustments or skipped compilation are reported as such even if the launch medians differ. Already compiled apps can have no new compilation benefit. Both phases retain their raw observations when a comparison becomes inconclusive. Runtime/app identity changes require a new baseline.

## Recovery and storage

Each captured sample is saved atomically in app-private storage. One latest session is retained; export JSON to retain additional sessions. Android WorkManager runs each user-requested phase in the foreground. A stopped/interrupted phase never becomes a complete comparison and is not silently replayed. Stop prevents later launches after the synchronous shell command returns; measurement launch commands have a 60-second process timeout. A compile command already submitted to Android can finish after Stop is pressed.

## Validation boundary

Host regressions and Android 17 emulator tests verify parsing, arithmetic, state, serialization, UI and recovery contracts. Emulator measurements are not Samsung performance evidence. A physical before/after capture is still needed on the user's SM-S918U1 to report actual launch changes. Existing uploaded 1.7.3 runs contain no startup samples, so their prior performance cannot be reconstructed.

Primary references:
- https://developer.android.com/topic/performance/issues/launch-time
- https://developer.android.com/tools/adb
- https://source.android.com/docs/core/runtime/configure/art-service

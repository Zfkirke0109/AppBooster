# S26 Ultra validation

The connected handset on October 26, 2026 is SM-S948U (`m3q`), Android 17/API 37,
One UI 9 (`90000`), build S948USQS4BZIG, patch 2026-10-05. DroidBridge 0.5.8
provides Shizuku shell UID 2000; root is unavailable. The initial installed version
was 1.7.4/10704. The update uses the existing 1.7.4 Android 17 code line, not the
older default branch.

## Observed hardware and ART

- QTI SM8850, platform `canoe`, arm64-v8a only, ART `oryon` / features `default`.
- 4096-byte pages; 11,350,976 KiB reported physical memory.
- CPU policy 0: CPUs 0-5, maximum 3,628,800 kHz. Policy 6: CPUs 6-7,
  maximum 4,742,400 kHz. No assumed S23 core mask is appropriate here.
- Google ART module 374200160. `speed`, `speed-profile`, `verify`, `--full`,
  verbose output and `-p PRIORITY_BACKGROUND` are advertised by the live help.
- Background dex2oat thread/CPU-set properties are empty. ART selects its defaults;
  empty properties do not mean that compilation is unavailable.
- Initial thermal status 0; battery 17%, charging, 30.7 C. A subsequent sample
  was 25%, charging, 31.7 C. Existing 35% battery and severe-thermal guards stay.
- The app's own primary dex initially reports `verify` / reason `install`.
  Bounded UID logcat reads succeeded but contained no app records. Exit history
  showed Samsung prelaunch and empty-process cleanup, not a recorded crash.

At 21:49 PDT, with battery 43% and thermal status 0, a single-package smoke test
against the installed OptiDroid 1.7.4 ran the new command shape:

```sh
cmd package compile -m speed-profile -f --full -v -p PRIORITY_BACKGROUND com.zfkirke0109.galaxyoptidroid
```

Shell exit was 0. ART reported `actualCompilerFilter=speed-profile`,
`status=PERFORMED`, wall time 364 ms, CPU time 1190 ms, size 9,070,712 bytes
(previous 3,853,952 bytes), and `Final Status: PERFORMED`. A fresh package dump
confirmed `arm64: [status=speed-profile] [reason=cmdline]`. This verifies the
command on the actual S26 and improves that package's observed compilation state;
it does not verify a new APK installation or demonstrate faster startup.
The original 1.7.4 APK was backed up on the phone and its SHA-256 matched the
installed APK. No app data, profiles, security protections or system properties
were reset.

## Update behavior

Version 1.8.0 detects compiler capabilities for every optimization mode and
requests background resource priority only when advertised. Android still selects
the ISA, affinity and dex2oat threads. Packages remain serialized. The exact
priority flag is retained in each step's existing command telemetry.

Unknown/legacy help retains the basic command. Full-scope mode continues to
require explicit capability evidence. Help parsing stops before the next command
so unrelated install options cannot enable compile flags. This is a resource
policy improvement; launch-time or battery improvements require measurements.

## Desktop validation helper

The existing helper keeps its S23 default and accepts an explicit expected model:

```powershell
.\tools\Invoke-S23Validation.ps1 -Action NewSession -ExpectedModel SM-S948U -Serial <ADB_SERIAL> -ApkPath <SIGNED_APK>
.\tools\Invoke-S23Validation.ps1 -Action StartCapture
.\tools\Invoke-S23Validation.ps1 -Action Install
.\tools\Invoke-S23Validation.ps1 -Action Launch
.\tools\Invoke-S23Validation.ps1 -Action Snapshot
.\tools\Invoke-S23Validation.ps1 -Action StopCapture
```

The historical `s23_` directory prefix remains compatible with existing sessions;
`session.json` records the actual and expected models. Snapshots include allowlisted
SoC/ABI/page-size/CPU-policy/ART facts. Capture no longer clears logcat buffers and
is restricted to OptiDroid execution tags. Supplement it with a bounded app UID
crash-log read when investigating an app failure.

## Runtime acceptance gates

1. Check package, version and signer; install as an update without clearing data.
2. With battery at least 35%, analyze and optimize a selected ordinary app using
   Shizuku, then check the actual per-container compiler filter and exported totals.
3. Confirm cancellation, low-battery and thermal guards, screen-off behavior, and
   legacy S23 command fallback. A full-device forced compile is unnecessary.
4. Use the existing measured-startup workflow for comparable before/after results.
   Do not infer a speedup from successful compilation or a shorter repeat run.

See [primary-source research](s26-ultra-art-research.md).

## Installed 1.8.0 and follow-up diagnostics

Version 1.8.0/10800 installed in place on October 26 at 23:17 PDT. Package Manager
accepted the update, retained the original first-install time and app-data inode,
and the installed APK SHA-256 matched the verified signed build. The observed main
activity launched successfully. A background-priority speed-profile smoke test
returned PERFORMED and a separate dexopt query confirmed the arm64 filter.

On October 27 the unlocked phone rendered the Settings and Dashboard screens.
An existing full-scope speed run exposed the actual app-generated command with
`-p PRIORITY_BACKGROUND`; its parent process was OptiDroid's Shizuku shell service.
The automatically exported canceled-run telemetry recorded 105 attempted commands,
all with background priority, 103 newly verified requested-filter results, 59
already-matching packages, one Android-adjusted filter, two not-applicable packages,
zero failed/refused commands and 435 unprocessed packages. Totals reconciled to 600.
The 162 matching results shown in the UI include the 59 already-matching packages.
No new app crash or ANR exit record was observed in bounded diagnostics.

The run continued after battery fell below the configured 35% guard. Source
inspection confirmed that battery and thermal state were checked only at batch
start; storage alone was checked before each compile. The run was stopped using
the app's normal Stop control. Version 1.8.1 rechecks the existing device guard
immediately before each compile, before marking that step running. Low battery
or severe thermal state then uses the existing paused-run path, preserving
completed counts and pending steps. An in-flight compile can finish before the
next boundary check. Regression cases reproduce battery and thermal changes after
the first successful compile. No battery or thermal readings were falsified on
the physical handset.

Landscape observation at the phone's 1.08 font scale also showed cramped wrapping
in the result card. This is a separate UI finding; it does not establish a compile
failure or a startup/battery improvement.

The unchanged app code passed all 342 JVM tests and all 27 Android 17 tests on the
PR run. The push run repeatedly hit the full Google APIs emulator's pre-test
`mapper.ranchu` DMA assertion. Runtime CI now uses the SDK's API 37 `google_atd`
image, retaining every instrumentation assertion and the SDK/shell identity
checks. This suite does not assert hardware-rendered screenshots. The full-image
navigation and thumbnail workarounds are no longer needed for the ATD job.
Physical Samsung observations remain separate from emulator runtime evidence.

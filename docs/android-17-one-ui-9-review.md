# Android 17 / One UI 9 review — 2026-09-19

## Device and evidence

The supplied screenshot and JSON identify Samsung SM-S918U1 (Galaxy S23 Ultra),
Android 17 / API 37, One UI 9.0, build `CP2A.260605.016.S918U1UEU8ZZI8`,
baseband `S918U1UEU8GZI8`, kernel `5.15.197-android13-8-34343818-abS918U1UEU8ZZI8`,
Android security patch 2026-08-05, Google Play system update 2026-09-01, and
SELinux enforcing. The export reports ART module version `2.1.0`.

The two supplied JSON files differ only in `exportedAtUtc`. They are two exports
of run `1789678261356`, not two independent optimization attempts. Their app
identity is `1.7.0` / `10700`; it does not identify an exact source commit.
The raw logs and installed-app inventory are intentionally not committed here.

## Run results and limitations

- Mode: Full Compile / DEXopt All, requested `speed`, full scope, force-selection off.
- Start/end: 2026-09-17 20:51:01.421–23:50:05.337 UTC (2h 59m 03.916s).
- 756 targeted; one analysis-time skip; 755 step records; 682 commands attempted.
- Reported: 654 successes, 76 total skips, zero failures, 26 OS-adjusted outcomes.
- Two attempted commands were classified as skipped by ART. The other 73
  preflight/skip records had no command; the totals must not equate “processed”
  with “attempted”.
- All 755 steps have a null package update timestamp.
- 21 command outputs contain multiple DEX-container results. Ten reported
  successes include at least one `verify` container. These are not complete
  full-scope `speed` successes, even where the base APK reached `speed`.
- Reported ART growth: 14,603,539,976 bytes (13.60 GiB). Summing all container
  `sizeBytes - sizeBeforeBytes` fields yields 14,837,847,668 bytes (13.82 GiB).
- Device free space declined by 16,793,677,824 bytes (15.64 GiB). Other device
  activity contributes to this observation; it is not an ART-only measurement.
- These exports provide no launch-time, frame-time, battery, or thermal comparison.
  They cannot establish a speedup or prove the absence of app crashes/ANRs.

## Goals and confirmed defects

The repository's goals are selective ART compilation, reliable skip decisions,
verified per-package results, durable progress, and bounded Shizuku replies.
Compatibility should follow observed capabilities rather than a model-specific
command whitelist or assumptions about the Android version.

1. The old global parser searched a 30-line window after a substring match.
   It could borrow a neighboring package's filter or match a longer package name.
2. Generic substring parsing accepted words such as `verification` and `speed`
   inside unrelated metadata. Samsung's package dumps also carry historical
   optimization entries for unrelated packages.
3. ART parsing used only the first container and its size. Full-scope verification
   could hide later containers that remained at `verify`.
4. The resolver returned from a global dump before reading update identity.
   Its session cache also fabricated the newly requested filter after any prior
   success, including a switch from `speed-profile` to `speed`.
5. The adjusted-outcome SQL treated two null update identities as a match,
   allowing an unknown/new app version to inherit an older skip decision.
6. `verify` was treated as proof that no profile exists. It only establishes the
   current compiler filter; profile availability must be left to ART.
7. A session analysis could be reused indefinitely after an app update or after
   completion emptied its work list. Every new run now resolves current package
   evidence before selecting work.
8. Adjusted outcomes could cross modes that share `speed`, and profile-based
   adjustments were treated as permanent. Cache reuse now requires the same mode;
   `speed-profile` and full-scope results are not permanent skips because profiles
   and secondary DEX can evolve without an APK update.
9. Truncated package evidence could hide a weaker container, and a metadata
   package header could obscure the later ART section. The parser keeps section
   boundaries and treats incomplete final blocks as unknown. Confirmed resource
   overlays retain their exclusion when ART provides no filter.
10. CI's Android setup default requested the removed `tools` SDK package and
   stopped before any tests. Explicit `platform-tools` setup fixes that failure.

`forceOptimize=false` and command `-f` are distinct existing settings: the former
controls selection and the latter forces the selected compilation. This review
preserves that documented command contract. It does not claim that a rootless
app can override Samsung's ART policy, CPU scheduling, SELinux, or Knox.

## Compatibility and release validation

### 1.7.2 scan regression correction

The 1.7.1 multi-container regex left its literal closing brace unescaped. The
desktop JDK accepted it, so all host unit tests passed, but Android's ICU engine
rejects it. Because the regex was a singleton initializer, the first package
scan failed immediately after the global DEXopt dump, before any filter could
be parsed. Version 1.7.2 escapes the brace and adds Android instrumented tests
for this runtime difference, multi-package scanning, and multi-container ART
results. Failure entries now display their diagnostic detail, and analysis
errors preserve an initializer's cause even when its own message is null.

Both signed build paths now require instrumented tests on an Android 17 / API 37
emulator. Compiling the tests or running them on the desktop JDK alone is not
sufficient. The emulator verifies the Android runtime and UI; it does not replace
a Samsung One UI / Shizuku check on the phone.

The API 37 emulator uses three-button navigation: its gesture-bar region
sampling crashes the emulator's `mapper.ranchu` graphics driver before tests
can run. This setting is confined to CI and does not change the app. Gesture
navigation and Samsung system UI behavior remain part of the phone check.

### Platform and device checks

The current project already compiles against SDK 37 and targets SDK 36.
Running on Android 17 does not require changing target SDK to 37. A target-SDK
migration should be tested separately against its behavior changes.

Capability probes for `--full` and `-v`, serialized compilation, the storage
reserve, device guards, and bounded Binder output remain relevant safeguards.
Normal `speed-profile` or a selected set of heavy apps avoids routinely repeating
this three-hour full-device compilation. Full `speed` uses substantially more
storage and does not guarantee faster execution for every workload.

A physical-device check is still required after CI passes:

1. Verify APK package/version/signing certificate before installing over the
   existing app; never uninstall or clear its data to solve a signing mismatch.
2. Analyze, then compile one selected non-system package with Shizuku.
3. Verify update identity is populated, current package evidence is isolated,
   and every verbose container is considered. Check that Stop still cancels.
4. Repeat analysis after an app update and after changing compilation mode.
   The previous result must not fabricate a stronger filter or hide a new version.
5. Check screen-off/background behavior and worker stop reasons on the phone.
   Android 16+ WorkManager long-running jobs remain subject to JobScheduler
   quotas; these logs do not prove unlimited background execution on Android 17.
6. Export the resulting telemetry. A full-device run is not needed to validate
   these fixes. No phone is connected to this workspace.

Primary platform references:

- [ART Service configuration](https://source.android.com/docs/core/runtime/configure/art-service)
- [Android 17 changes for all apps](https://developer.android.com/about/versions/17/behavior-changes-all)
- [Long-running WorkManager jobs](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running)

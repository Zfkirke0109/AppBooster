# S26 Ultra support

## Objective and scope

Adapt the installed Galaxy OptiDroid 1.7.4 code line to the connected SM-S948U
on Android 17/API 37. Preserve S23 and older Android support, app data, package
identity, platform-managed Oryon ISA/thread selection, and existing device guards.
The user's requested update authorizes this focused implementation.

## Acceptance criteria

- Detect background compilation priority from the actual compile help section.
  Use it for optimization when advertised; preserve the legacy command when
  help or that option is unavailable. Do not set CPU masks or system properties.
- Keep serialized compilation, post-command verification, cancellation, battery,
  thermal, and storage guards.
- Accept an explicitly selected S26 model in release validation while keeping
  the S23 default compatible. Capture SoC, ABI, page size, CPU policies, and ART
  configuration as read-only evidence.
- Preserve captured-device facts separately from benchmark claims. Do not
  commit private logs, device serials, installed app inventories, or artifacts.

## Implementation and verification

1. Add S26 help and command-allowlist regressions, then capability-gated priority
   in the domain command, data parser, optimization and measurement workflows.
2. Extend the validation helper and document the real device baseline.
3. Run `./gradlew --no-daemon runUnitTests :app:lintDebug :app:assembleDebug
   :app:compileDebugAndroidTestKotlin`; inspect diff and keep the change on a
   separate branch based on `codex/one-ui-9-art-evidence` (installed 1.7.4).
4. Check a signed update's package/version/certificate before an in-place
   install; never uninstall to resolve a signing mismatch. Runtime and startup
   benchmarks must be reported as untested until actually completed.

## Structure and conventions

Production Kotlin remains under `app/src/main/java/com/tony/appbooster`, pure
domain command models stay free of Android imports, JUnit 4 tests remain under
`app/src/test`, and device procedures belong under `tools` and `docs`.
Use typed argv (for example `PackageCompile(..., background = true)`), constructor
injection, bounded output, and existing source style. No dependency or target-SDK
migration is needed for this task.

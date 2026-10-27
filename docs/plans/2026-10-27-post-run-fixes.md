# Post-run correctness and landscape controls

## Scope

Review the completed S26 Ultra 1.8.1 run, retained Logcat, and the S26 changes in
PR #9. Preserve the installed app's data, completed results, shell allowlist,
serialized execution, cancellation, device guards, and storage reserve.

## Confirmed defects and acceptance criteria

1. A zero shell exit can accompany ART `FAILED`, `CANCELLED`, or `CANCELED`.
   Explicit container or final failure status must override a matching filter.
   Record a failed step and continue the batch; do not mark its resolver cache
   optimized. A measurement with this outcome must not authorize its after phase.
2. An explicitly rejected optional `--check` probe repeats for unresolved packages.
   Remember that capability for the resolver's lifetime, including metadata resets.
   Inspect both output streams. Other failures remain retryable and visible;
   only the recognized capability rejection uses diagnostic rather than error logging.
3. Unbounded overlay-name substrings exclude executable apps such as the synthetic
   `com.example.arrow` fixture. `HAS_CODE` evidence overrides naming heuristics,
   and name hints use complete RRO/overlay components. Explicit resource-overlay
   metadata remains excluded when it contains no executable-code evidence.
4. The landscape result pane cannot scroll to its actions. Make it independently
   scrollable, and let result text and actions use the available narrow-pane width.
   Run and re-optimize actions must remain reachable at 832 x 320 logical bounds,
   with font scales 1.08 and 1.5. Preserve count and dismiss behavior.

## Verification

- The parser and repository regressions failed on the original implementation:
  the red CI run completed 350 JVM tests with the eight new failures.
- Both new landscape tests failed on Android 17 with no ancestor scroll action;
  the other 27 instrumented tests completed without failures.
- An actual-source Kotlin resolver harness reproduces repeated capability probes
  and the executable-overlay false exclusion before their fixes.
- Run the full JVM suite, lint, debug build, instrumentation compilation, and all
  Android 17 runtime tests before release signing.
- Verify the signed 1.8.2 package, version, and release certificate before an
  in-place update. Retain the completed device result and verify its controls.

Private telemetry, app inventories, Logcat, APKs, and device-specific install paths
are not source artifacts and must not be committed.

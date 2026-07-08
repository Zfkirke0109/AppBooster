---
name: android-gradle-ci-debugging
description: Diagnose and fix Gradle, Kotlin, or Android CI build failures — compileDebugKotlin errors, testDebugUnitTest failures, assembleDebug failures, GitHub Actions build/artifact issues, AGP or JDK version mismatches. Use whenever the user pastes a build error, mentions a red CI check, asks why a Gradle task is failing, or asks about dependency/toolchain issues in this Android project. Trigger even when the user just pastes a stack trace or error log without naming Gradle explicitly.
---

# Gradle / Kotlin / Android CI Debugging

## Why a conservative approach matters here

Build failures are tempting to "fix" by upgrading everything in sight (AGP, Kotlin, dependencies) until the error goes away. In a project with a working, previously-green CI pipeline, that's usually the wrong move — it trades a understood, scoped failure for an unscoped set of new compatibility risks, and it makes the diff much harder to review. The default here is: find the specific, minimal cause and fix that.

## When to use

- A pasted Gradle/Kotlin/Android build error or stack trace.
- CI (GitHub Actions) shows a red check on this repo.
- Questions about AGP, Kotlin, or JDK version compatibility.
- `compileDebugKotlin`, `testDebugUnitTest`, or `assembleDebug` failures specifically.

## What to check, in order

1. **Read the actual failure first**, not just the exit code — `./gradlew` output usually names the specific task and file. Don't start hypothesizing before you've read it.
2. **Gradle wrapper** — confirm `gradlew`/`gradlew.bat` and `gradle/wrapper/gradle-wrapper.properties` are consistent and unmodified unless the failure is actually about the wrapper.
3. **AGP/Kotlin compatibility** — check `build.gradle(.kts)` / version catalog for known-incompatible combinations, but only change versions if the failure is actually a compatibility error, not preemptively.
4. **JDK version** — check `gradle/gradle-daemon-jvm.properties` or the configured toolchain against what's installed/expected in CI.
5. **Missing dependencies** — a genuinely missing or misdeclared dependency versus a resolution/repository configuration issue are different problems with different fixes.
6. **Deprecation warnings vs. real failures** — a build can go red because of a genuine compile error while unrelated deprecation warnings are noise. Don't "fix" warnings that aren't causing the failure as part of this task.
7. **`compileDebugKotlin` errors** — usually a real type/syntax issue; read the exact line.
8. **`testDebugUnitTest` failures** — read which specific test failed and why; don't treat a legitimately failing assertion as a flaky-test problem without evidence.
9. **`assembleDebug` failures** — often resource, manifest, or signing config issues distinct from compile errors.
10. **GitHub Actions artifact/check status** — `gh run view` / `gh pr checks` to see what CI actually reported, not just what reproduces locally.

## Rules

- Don't upgrade AGP, Kotlin, or other major dependencies unless the failure genuinely requires it. If you believe an upgrade is the right fix, say so explicitly and why, rather than just doing it.
- Don't edit `gradlew`/`gradlew.bat` file contents unless the failure is actually caused by the wrapper itself.
- Changing the executable bit on `gradlew` is fine when that's the actual problem (e.g., CI reports "permission denied") — that's a narrow, justified change, not a wrapper content edit.
- Keep the existing debug signing configuration intact — don't touch signing unless the task is specifically about signing.
- Preserve the existing CI workflow file structure unless it's the thing that's actually broken.

## Output format

- **Failing task** — the exact Gradle task/CI job that failed.
- **Root cause** — specific, not "dependency issues" but which dependency, which version conflict, which line.
- **Exact files changed** — list them.
- **Commands run** — so the user can reproduce your verification.
- **Remaining warnings** — anything noted but intentionally left alone, and why.

## Escalation — stop and ask when

- The only fix you can find requires a major version bump (AGP, Kotlin, JDK) — confirm before doing it, since it has ripple effects beyond this one build.
- The failure seems to require changing signing configuration or CI workflow permissions.
- You can't reproduce the CI failure locally and aren't sure why local and CI environments diverge — say so rather than guessing.

## Anti-patterns

- Don't run `./gradlew build --refresh-dependencies` and other broad "just try things" commands as a substitute for reading the actual error.
- Don't bump dependency versions speculatively "to be safe."
- Don't silence a warning or add a suppression annotation instead of fixing the actual cause, unless the user explicitly asks for that.
- Don't claim a build passes without having actually run it in this session — see [[repo-safety-guardrails]].

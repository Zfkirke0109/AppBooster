---
name: android-logcat-device-validation
description: Turn Android Logcat/ADB output into an actionable root cause and fix plan. Use whenever the user pastes logcat output, mentions ADB (wireless or Termux), asks to debug on a Samsung Galaxy S23 Ultra or other real device, references MediaCodec/Transformer failures, notification spam or shedding, foreground service crashes, or asks "why did this crash on my phone" / "is this a real bug or just noise." Also use proactively whenever real-device validation is mentioned as a remaining step in a PR.
---

# Logcat & Device Validation

## Why this matters

Real Android devices — especially Samsung phones — produce a lot of log noise from OEM services that has nothing to do with the app being debugged. An agent that treats every log line as equally suspicious will chase phantom bugs in vendor code and miss the actual crash. This skill exists to separate signal from noise quickly and turn what's left into a concrete fix.

## When to use

- User pastes or describes logcat output.
- User is debugging over wireless ADB or Termux ADB.
- The device in question is a Samsung Galaxy S23 Ultra (or similar OEM-heavy device).
- MediaCodec, Media3 Transformer, notification, or foreground-service behavior is in question.
- A PR lists real-device validation as a remaining step (see [[android-kotlin-pr-continuation]]).

## Inputs to request (if not already given)

- The exact app package name, if it's ambiguous which app (Compressor vs AppBooster vs something else).
- Whether ADB is wireless or via Termux, and whether it's already connected.
- The specific symptom: crash, ANR, silent failure, unexpected notification behavior, etc.

## Standard commands

```
adb logcat -v time -s CompressorBatch CompressorEncoderPlan CompressorVerification CompressorLearning Transformer MediaCodec
adb logcat -v time -s NotificationService OptimizationWorkerNotification WorkForegroundNotificationHelper
adb shell dumpsys media.codec
adb shell dumpsys media.metrics
adb shell dumpsys battery
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
```

Adjust the `-s` tag filter to match whatever component is actually in question — these are the common ones for Compressor (video pipeline) and AppBooster (foreground service / notifications), not an exhaustive list.

## Workflow

1. **Get the exact package name if it's unclear.** Don't guess between Compressor and AppBooster (or another app) — ask.
2. **Filter for the app's own tags first.** Start with the app-specific logcat tags above before widening the net.
3. **Separate real app failures from Samsung/OEM noise.** Lines referencing `libpenguin.so`, `vendor.display.*`, or similar Samsung system-service internals are *not* app failures by themselves. Only treat them as relevant if they appear paired with an actual app crash, ANR, or export/encode failure in the same time window — correlation in time matters, not just presence in the log.
4. **Convert the surviving signal into a concrete diagnosis:**
   - Root cause (what actually happened, in plain terms)
   - Likely file/class in the codebase responsible
   - Fix plan (specific, scoped — not "refactor the pipeline")
   - Validation plan (what to re-run, on-device or otherwise, to confirm the fix)
5. **For MediaCodec/Transformer failures**, cross-check against [[android-media3-perceptual-compression]] — many "failures" here are actually the honest verification-fallback path working as intended (see the truth rules there), not bugs.
6. **For notification/foreground-service issues** (more common in AppBooster), check for enqueue-rate shedding, WorkManager foreground notification update patterns, and whether the issue is genuinely a bug versus expected Android OS throttling behavior.

## Rules

- Don't treat `libpenguin.so`, `vendor.display.*`, or other clearly OEM-namespaced log lines as app bugs on their own.
- Always get the exact package name before drawing conclusions if there's any ambiguity.
- State your confidence — "this log line is almost certainly OEM noise" reads differently from "this is the actual crash," and mixing them up wastes the user's time chasing the wrong thing.
- If you don't have a real device/ADB session available, say so rather than inventing what the logs would probably show.

## Output format

- **Root cause** — plain-language explanation.
- **Likely file(s)** — specific paths/classes.
- **Fix plan** — scoped, concrete steps.
- **Validation plan** — exact commands/steps to confirm the fix, on-device where relevant.
- **Noise filtered out** — briefly note what you discounted as OEM/system noise and why, so the user can sanity-check that call.

## Escalation — stop and ask when

- The package name is ambiguous and matters for which logs are relevant.
- A crash could plausibly be either an app bug or a genuine hardware/OEM limitation (e.g., an HDR-related MediaCodec quirk) and you can't tell without more device-specific testing than you have available.
- Fixing the root cause would require touching code covered by [[android-media3-perceptual-compression]]'s truth rules — hand off to that skill's constraints rather than patching around them.

## Anti-patterns

- Don't diagnose a bug purely from a screenshot or description without asking to see the actual logcat output if it's available.
- Don't treat every stack trace mentioning a Samsung/vendor class as the root cause — check whether it's just in the call chain incidentally.
- Don't recommend a fix that touches encode/verification logic without cross-checking [[android-media3-perceptual-compression]] first.

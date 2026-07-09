---
name: android-security-privacy-review
description: Review Android code changes (in Compressor, AppBooster, or similar) for security and privacy issues — permissions, storage access, Shizuku/root behavior, file-overwrite safety, metadata/location leakage, dangerous shell execution, signing/keystore material, or new network/third-party dependencies. Use whenever a change adds a permission, touches file replacement logic, adds logging that might include paths or location, adds a new dependency, or the user asks for a security/privacy pass. Trigger proactively before merging anything that touches these areas, even if not explicitly asked.
---

# Security & Privacy Review

## Why this is a review skill, not a lockdown skill

The goal here is to catch real risks without becoming an obstacle to normal development — flag genuine issues clearly, and don't manufacture concern over things that are actually fine. A false alarm that gets ignored is worse than no review at all, because it trains the user to skim past your findings.

## When to use

- A diff adds or changes a permission.
- Storage access, file overwrite, or "replace original" logic is touched.
- Logging statements are added that might include file paths, GPS, or other personal data.
- A new dependency, third-party binary, or network call is introduced.
- Signing configuration or keystore-adjacent code is touched.
- Shizuku/root-adjacent behavior is involved.
- The user explicitly asks for a security or privacy review.

## What to check

- **Permissions** — is a new permission actually necessary for the stated feature, and is it the narrowest one that would work?
- **Storage access** — scoped storage compliance, no broader access than needed.
- **Shizuku/root behavior** — don't assume root or Shizuku availability as a requirement; these should be optional enhancements, not silent assumptions.
- **Replacement of originals / file overwrite safety** — destructive by nature; needs verification-gating and ideally a backup path (cross-reference [[android-media3-perceptual-compression]] and [[android-compose-ui-safety]] for how this applies to Compressor specifically).
- **Metadata/location leakage** — does any code path log, transmit, or persist GPS/location or other personal metadata unnecessarily?
- **Logs leaking private paths or location** — check new log statements specifically for this.
- **Dangerous shell execution** — any `Runtime.exec`, `ProcessBuilder`, or similar should be scrutinized for injection risk and necessity.
- **Signing material / keystore leaks** — nothing resembling a keystore, private key, or signing password should ever land in a commit.
- **Network/internet additions** — a new network call or newly-added `INTERNET` permission in a project that's supposed to be offline-first is a strong signal to stop and ask.
- **Third-party binaries** — new native libraries or bundled binaries need a clear justification.

## Compressor-specific rules

- **No FFmpeg unless the user explicitly approves it in this conversation** — this project relies on Media3/MediaCodec by design; adding FFmpeg is a significant architectural and licensing decision, not a routine dependency add.
- **No `INTERNET` permission** — this app's compression pipeline is local-only; a new network permission here is a red flag, not a convenience.
- **No invasive storage permission** beyond what scoped storage requires for the feature at hand.
- **Never log GPS/location.** Location handling should follow whatever privacy mode is already in place in the code — don't add a log line that bypasses it.
- **Never replace originals unless verified and backed up** — this ties directly into the truth/verification rules in [[android-media3-perceptual-compression]]; a replace-original path that isn't gated on real verification is both a correctness bug and a data-loss risk.
- **Preserve existing privacy modes** — don't add a code path that reads or reports data a privacy mode is supposed to suppress.

## AppBooster-specific rules

- **Watch for foreground-service notification spam** — repeated or rapid-fire notification updates are both a UX problem and, at volume, can trigger OS-level notification shedding.
- **Watch WorkManager foreground notification update patterns** specifically — these are a common source of the spam issue above.
- **Avoid notification enqueue-rate shedding** — if a change could cause notifications to be posted faster than the OS will reliably deliver them, that's worth flagging.
- **Don't add root-only assumptions** — any feature that only works with root should degrade gracefully without it, not assume its availability.

## Output format

- Findings ranked by severity — real issues first, minor notes after.
- For each finding: what it is, why it matters (concretely — what could go wrong), and the specific file/line.
- Explicitly note when you checked something and found no issue — this tells the user the area was actually reviewed, not skipped.

## Escalation — stop and ask when

- You find something that looks like a committed secret, key, or credential — flag immediately and recommend rotation, don't just note it for later.
- A change would add `INTERNET` permission or any new network destination.
- A change would add FFmpeg or another significant new dependency in a security-sensitive area.
- A "replace original" path appears reachable without verification gating.

## Anti-patterns

- Don't flag routine, well-scoped permission usage as a risk just because permissions are involved at all.
- Don't block a change purely on the presence of `ProcessBuilder`/`exec` without checking whether the arguments are actually attacker-influenced or fixed/trusted.
- Don't treat this as a checklist to rubber-stamp — actually reason about whether each item applies to the specific diff in front of you.

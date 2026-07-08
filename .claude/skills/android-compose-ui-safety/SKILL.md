---
name: android-compose-ui-safety
description: Review or fix Jetpack Compose UI code without introducing behavior regressions — state hoisting, recomposition issues, accessibility, dark mode, destructive-action safeguards, or honest labeling of lossy vs. lossless modes in the Compressor app. Use whenever the user is touching Compose UI code, asks about a UI bug, mentions a control that seems disabled or confusing, or is working on FPS chips, quality-mode labels, or the "replace original file" flow. Trigger even for small UI tweaks — a "just cosmetic" Compose change can easily hide a state bug.
---

# Compose UI Safety Review

## Why this is its own skill

Compose UI bugs are easy to introduce invisibly — a recomposition issue or a hoisting mistake can look fine in a quick visual check and only surface under specific state transitions. In this app specifically, UI labels are also a correctness surface: a mislabeled control can make a lossy operation look lossless to the user, which is the same category of problem as the encode-pipeline truth rules in [[android-media3-perceptual-compression]], just expressed in the UI instead of the encoder.

## When to use

- Any Compose UI file is being added, reviewed, or modified.
- A control seems to behave unexpectedly across recompositions.
- The user mentions a UI bug, layout issue, or accessibility concern.
- Work touches FPS chips, quality-mode selectors, or the replace-original-file flow.

## What to check

- **State hoisting** — is state owned at the right level, or duplicated/shadowed in a way that could desync?
- **Recomposition problems** — unnecessary recompositions, or state reads that skip recomposition when they shouldn't.
- **Disabled controls have explanatory text**, not just a greyed-out appearance with no reason given.
- **Labels are honest** — a control's label must accurately describe what the underlying operation does (see the Compressor-specific rules below).
- **Accessibility** — content descriptions present, text scalable, tap targets reasonable.
- **Long text overflow** — labels/descriptions handle truncation or wrapping sensibly, especially on smaller screens.
- **Dark mode** — visual review in both themes if the change touches colors/surfaces.
- **Mobile layout safety** — check against realistic phone widths, not just a wide preview.
- **Progress indicators** — present and accurate for long-running operations (encoding), not left indeterminate when real progress is available.
- **Destructive toggles** (e.g., replace-original) — require deliberate confirmation, not a single accidental tap.

## Compressor-specific rules

- **FPS chips must be disabled with an explanation in Smart Perceptually Lossless** whenever selecting one would imply lossy frame dropping — PL always preserves source FPS, so an FPS chip that could contradict that needs to be inert and explained, not just hidden or silently ignored. Cross-check the actual encode-planning behavior against [[android-media3-perceptual-compression]] before assuming the UI is wrong — the disabling logic should match the mode-aware FPS planning documented there.
- **High Quality and Storage Saver must clearly state they are lossy.** No ambiguous wording that could be read as "lossless."
- **Remux Only must state it performs no re-encode and may not reduce file size.** A user should not be surprised that a Remux Only pass didn't shrink their video.
- **Replace-originals must show a safety/backup warning** before it can proceed — this is a destructive, irreversible action against the user's own media.

## Output format

- What was reviewed / changed.
- Any state-hoisting or recomposition concerns found, with the specific composable.
- Any label-honesty issues found, with what the label currently says vs. what the underlying mode actually does.
- Accessibility/dark-mode/overflow notes if relevant to the change.
- Whether destructive-action safeguards are intact.

## Escalation — stop and ask when

- A label change would require changing what a mode is documented to do — that's an [[android-media3-perceptual-compression]] change, not just a UI change, and needs to go through those truth rules.
- A destructive-action confirmation flow (replace-original) needs to be removed or weakened — don't do this without explicit sign-off.

## Anti-patterns

- Don't fix a "cosmetic" issue by hiding a control instead of explaining why it's disabled — that removes information the user needs.
- Don't relabel a mode to make it sound better without verifying the label still matches actual behavior.
- Don't remove a confirmation step on a destructive action to "streamline" the flow.
- Don't assume a UI bug report is purely visual without checking whether it traces back to a state-hoisting issue.

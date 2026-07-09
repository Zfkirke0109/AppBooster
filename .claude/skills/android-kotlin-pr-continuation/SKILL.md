---
name: android-kotlin-pr-continuation
description: Continue an existing Android/Kotlin pull request (this repo, or AppBooster) without restarting the work from scratch. Use whenever the user asks to "continue the PR", "pick up where the last session left off", "finish PR #N", references a branch that already has commits, or asks for a status update on in-progress Android work. Also use when a previous Claude Code or Copilot agent session appears to have been interrupted (quota limit, context limit, crash) and left a branch mid-task. Triggers even if the user just says "keep going" or "what's left on this PR" without naming the skill.
---

# Android/Kotlin PR Continuation

## Why this exists

Android PRs in this workflow are frequently picked up across multiple agent sessions (Claude Code, GitHub Copilot, different quota windows). The single biggest failure mode is an agent that doesn't read the existing state first: it re-does work that's already correct, "fixes" things that aren't broken, or loses track of what real-device validation still needs to happen before a PR can leave draft status. This skill exists to make "read before you write" the reflexive first move.

## When to use

- User asks to continue, resume, or finish a PR/branch that already has commits.
- User asks "what's the status of PR #N" or "what's left to do".
- The current branch's git history suggests a prior agent session (commit messages referencing Copilot, Claude, or partial fixes).
- A PR is still marked draft and the user wants to know if it's ready.

## Inputs to request (only if not already obvious from context)

- Which repo (Compressor vs AppBooster vs other) and which PR number or branch name.
- Whether the user wants a status report only, or wants you to actively keep working.

## Step-by-step workflow

1. **Inspect before touching anything.**
   - `git status` and `git branch --show-current` — confirm you're on the right branch and the tree is clean.
   - `gh pr view <N> --json title,state,isDraft,body,files,comments,reviews` (or `git log` on the branch if there's no PR yet) — read the full PR body, not just the title. The body is usually the previous agent's own account of what it did and what remains; treat it as the primary source of truth.
   - `gh pr checks <N>` or check the repo's CI status for the branch — know whether the last build passed before you assume anything is broken.
   - `git diff origin/main...HEAD --stat` — see the actual scope of changes already made.

2. **Reconstruct the previous agent's state.** From the PR body and commit history, identify:
   - What was explicitly finished and verified (look for phrases like "PASS", "Verified", specific test/build results with real output, not just claims).
   - What was left open — PR bodies in this workflow tend to end with an explicit "Remaining" or "Still open" list. Trust that list over your own guess.
   - Whether real-device validation (S23 Ultra, ADB) was part of the plan and whether it's done. If real-device items remain, the PR should stay draft regardless of code quality — code correctness and device validation are separate gates.

3. **Do not duplicate completed work.** If the PR body says a fix already landed and the diff confirms it, don't redo it, re-explain it, or second-guess it without a concrete reason (e.g., a failing test that contradicts the claim). If you find a contradiction, that's worth flagging explicitly, not silently overwriting.

4. **Fix only what is actually broken.** Use the validation commands below to establish ground truth before changing anything. A warning is not automatically a bug; a passing test that "looks concerning" is not automatically wrong. Change code because you found a concrete failure, not because a broader refactor occurred to you — that's out of scope for a continuation task and makes the diff harder to review.

5. **Preserve valid prior work.** Small surgical diffs on top of what's there beat rewriting files. If a prior agent's approach was sound but incomplete, extend it rather than replacing it, unless it's actually wrong.

6. **Update the PR body to reflect exact current status** — don't just add a comment, edit the body so it stays the single source of truth for the next session (including a future you). State plainly what's now done, what's still open, and whether it's real-device-validated or just locally build-validated. Do not claim a test or validation step happened unless you actually ran it in this session.

7. **Decide draft vs. ready.** Keep the PR in draft if:
   - Any listed real-device validation item is still open, or
   - Any test/build command hasn't actually been run in this session, or
   - There's a known-honest fallback path (e.g., a "verification failed, kept safe fallback" case) that hasn't been exercised.
   Only mark ready for review once every gating item has genuine, stated evidence.

## Validation commands

Run these from the module root before reporting any result as "passing":

```
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
git diff --check
git status
```

If a command fails or wasn't run, say so explicitly — never write "tests passed" unless you saw the passing output in this session. See [[repo-safety-guardrails]] on this.

## Output format

Report back in this shape:

- **Current branch/PR state** — branch name, PR number if any, draft or ready.
- **What was already done** — summarized from the PR body + confirmed by diff.
- **What remains** — the open items, carried over accurately, not paraphrased into something vaguer.
- **Files changed** — this session's diff, distinct from the pre-existing diff.
- **Tests run** — exact commands and their actual results.
- **Build result** — pass/fail, with the failure output if it failed.
- **Draft/ready recommendation** — and why, tied to the gating criteria above.

## Escalation rules — stop and ask the user when

- The PR body's claimed status contradicts what you observe in the code or test output.
- You can't tell which of several open branches/PRs the user means.
- Finishing the "remaining" list would require a change bigger than what the PR originally scoped (e.g., a refactor, a new dependency, a permission change) — see [[android-security-privacy-review]] and [[repo-safety-guardrails]].
- Real-device validation is required but you have no device/ADB access in this session — say so rather than guessing at results.

## Anti-patterns — do not do this

- Don't open with a rewrite "while I'm in here" — that's a different task.
- Don't mark a PR ready for review because the code compiles; compiling is not the same as validated.
- Don't silently drop an item from the "remaining" list because it looks done to you — verify it or keep it listed as open.
- Don't invent test output. If you didn't run it this session, don't report a result for it.

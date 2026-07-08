---
name: github-copilot-pr-review
description: Review a GitHub pull request — including ones created by GitHub Copilot's coding agent or a prior Claude session — to decide whether it should merge, stay in draft, or needs specific fixes first. Use whenever the user asks to review a PR, asks "is this ready to merge," pastes a PR link or number, or wants a second opinion on an agent-authored branch before merging it. Also use before recommending anyone merge a PR in this repo.
---

# GitHub Copilot / Agent PR Review

## Why this is a distinct step from continuation

[[android-kotlin-pr-continuation]] is about *doing* the remaining work on a PR. This skill is about *judging* a PR — including ones you didn't work on yourself — well enough to give an honest merge/no-merge recommendation. The two are related but the judgment here needs to be a little more skeptical, especially for PRs authored by an agent (Copilot or otherwise) that you don't have first-hand context on.

## When to use

- User asks for a PR review or a merge recommendation.
- A PR was created by GitHub Copilot's coding agent or another automated/agent session.
- The user pastes a PR number or link and asks "is this good."

## Workflow

1. **Inspect the full PR** — body, changed files, existing comments, existing reviews, and CI check status:
   ```
   gh pr view <N> --json title,state,isDraft,body,files,comments,reviews
   gh pr checks <N>
   ```
2. **Confirm draft status and mergeability** explicitly — don't assume; check the actual API/CLI output.
3. **Look for accidental files** — build artifacts, IDE config, stray logs, anything that clearly shouldn't be in a source diff.
4. **Check for generated files** committed where they probably shouldn't be (unless the project convention is to commit them — check existing patterns first).
5. **Check `gradlew`/`gradlew.bat` specifically** — permission bit changes and content changes are different things; a content change to the wrapper script is a bigger red flag than an executable-bit fix. See [[android-gradle-ci-debugging]] for what's normal here.
6. **Separate the review comments that matter from ones that don't.** If asked to act on review feedback, only fix the comments that identify a real issue — don't treat every comment as mandatory if some are just style opinions the user hasn't endorsed.
7. **Confirm build and validation requirements before recommending merge** — CI green is necessary but check whether real-device validation was also part of this PR's stated scope (see [[android-kotlin-pr-continuation]]); if so, CI passing alone isn't sufficient.

## Output format

- **Merge now / do not merge / keep draft** — a clear verdict, not a hedge.
- **Why** — the specific evidence behind the verdict.
- **Required fixes** — concrete, prioritized, only the ones that actually block merging.
- **Suggested PR comment** — a draft the user can post as-is or edit.
- **Suggested next agent prompt** — if more work is needed, a self-contained prompt for whichever agent picks this up next (so it doesn't have to re-derive context).

## Escalation — stop and ask when

- CI is green but the PR's own body lists real-device validation as incomplete — don't recommend merge just because the automated checks pass.
- You find something that looks like a secret, credential, or signing material in the diff — flag immediately, don't just note it in passing (see [[repo-safety-guardrails]] and [[android-security-privacy-review]]).
- The PR touches compression/verification logic covered by [[android-media3-perceptual-compression]] — check its truth rules specifically before endorsing a merge.

## Anti-patterns

- Don't recommend merging based on the PR title/description alone without checking the actual diff.
- Don't treat "Copilot wrote it" or "an agent wrote it" as either automatically trustworthy or automatically suspect — evaluate the actual content.
- Don't merge PRs yourself unless the user explicitly says to merge — this skill produces a recommendation, not an action, by default.
- Don't rubber-stamp a PR just because its stated goal sounds reasonable; check whether the diff actually delivers it.

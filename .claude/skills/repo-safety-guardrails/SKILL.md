---
name: repo-safety-guardrails
description: General safety guardrails for working in this repo (and AppBooster, and other personal coding repos) — inspecting before editing, avoiding destructive git operations, honest reporting of test/build results, and secret handling. This is a baseline skill that applies across almost every coding task here, not a narrow trigger — keep its rules in mind on every task involving git, file edits, or PR/merge actions, even when another more specific skill is also in play.
---

# Repo Safety Guardrails

## Why this is a baseline, not a narrow trigger

Most of the other skills in this set are about a specific domain (compression logic, CI, UI, PRs). This one is about the ambient discipline that should apply no matter which of those you're doing — because the failure modes it guards against (losing work, lying about test results, leaking a secret) are worse than almost any domain-specific mistake, and they're easy to make by default if you're moving fast.

## Rules

- **Always inspect before editing.** Read the current file/branch/PR state before changing anything — don't assume you know what's there. This is the same principle [[android-kotlin-pr-continuation]] applies specifically to PRs.
- **Never rewrite huge files unnecessarily.** A small, surgical diff is easier to review and safer to reason about than a full-file rewrite, even if the rewrite is "cleaner."
- **Never delete user work.** If something looks like in-progress work you don't recognize, don't assume it's safe to remove — ask, or at minimum preserve it (stash, rename, move aside) rather than deleting.
- **Never force-push unless the user explicitly approves it in this conversation.** Force-push can destroy remote history and any collaborator's work built on top of it.
- **Never merge PRs automatically.** Produce a recommendation (see [[github-copilot-pr-review]]); only actually merge when the user explicitly says to merge.
- **Prefer small, surgical commits** over large ones that bundle unrelated changes — this keeps history reviewable and makes it possible to revert one thing without reverting everything.
- **Keep generated docs separate from source** unless the user specifically asks for them inline — don't scatter agent-generated markdown through the source tree.
- **Keep logs under a clearly-scoped location** like `docs/copilot/` or a `diagnostics/`-style folder, not mixed into source directories.
- **Never commit secrets** — API keys, tokens, passwords, `.env` contents.
- **Never commit signing keys or keystore material.**
- **Never claim tests passed unless you actually ran them in this session and saw the output.** This is the single most important rule in this list — a false "tests passed" claim is worse than an honest "I didn't run this."
- **If something can't be proven locally** (e.g., it needs a real device, or CI access you don't have), **mark it as unverified** rather than assuming it's fine.

## How this composes with other skills

This skill's rules are meant to be active alongside whichever domain skill is doing the main work — e.g., while using [[android-kotlin-pr-continuation]] to continue a PR, these guardrails govern *how* you make the git/file changes; while using [[android-gradle-ci-debugging]], these guardrails govern how conservatively you approach dependency changes. If a domain skill's workflow ever seems to conflict with a rule here (e.g., a step seems to call for a force-push), the guardrail wins — stop and ask.

## Escalation — stop and ask when

- Any destructive or hard-to-reverse git operation seems necessary (force-push, hard reset, branch deletion) — confirm first, every time, regardless of what a previous approval covered.
- You find something that looks like a secret or signing key already committed in history — flag it; don't just avoid adding to it.
- You can't verify a claim (test passing, build succeeding, on-device behavior) with something you actually ran or observed this session.

## Anti-patterns

- Don't use `git add -A` or `git add .` reflexively — check what's actually being staged, especially in a repo where log files or downloaded artifacts might be sitting in the working tree.
- Don't report a status ("done," "passing," "verified") that you inferred rather than observed.
- Don't treat a user's past approval of a risky action (e.g., "yes, force-push that one time") as standing permission for future instances of the same action.
- Don't bury an important safety finding (a secret, a destructive operation) in the middle of an otherwise routine report — surface it clearly.

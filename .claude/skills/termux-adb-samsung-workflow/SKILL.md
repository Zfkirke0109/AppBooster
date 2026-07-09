---
name: termux-adb-samsung-workflow
description: Give copy-paste-ready Termux commands for Android development — git, GitHub CLI auth, wireless ADB, Samsung Galaxy S23 Ultra debugging, log file handling, or running Gradle from Termux. Use whenever the user is working from a Termux shell, asks for a command to run on their phone/Termux, mentions wireless ADB setup, or needs to move log files between the device and a repo. Trigger even for short asks like "give me the command to reconnect ADB."
---

# Termux / ADB / Samsung Workflow

## Why this skill is command-first

This workflow happens directly in a Termux terminal on the user's phone, often mid-task with limited screen space and patience for prose. The value here is a correct, copy-pasteable command block, not an essay. Match that — lead with the command, explain only what's necessary to use it safely.

## When to use

- User is in Termux and needs a command.
- Wireless ADB setup, reconnection, or troubleshooting.
- Moving files (especially logs) between the device and a git repo.
- Git/GitHub operations from Termux specifically (auth quirks differ from a desktop shell).
- Running Gradle tasks from Termux where feasible.

## Core command patterns

**Package management:**
```
pkg update && pkg upgrade
pkg install <package>
```

**Git:**
```
git clone <url>
git pull
git add <files>
git commit -m "<message>"
git push
```

**GitHub auth — critical gotcha:** a normal GitHub account password will not work for `git push` over HTTPS. Use `gh auth login` (interactive) or a personal access token as the password when prompted. If the user hits an auth failure, this is almost always the cause — check it first before troubleshooting anything else.

**Wireless ADB:**
```
adb pair <ip>:<port>
adb connect <ip>:<port>
adb devices
```

**Copying logs from Downloads into the repo:**
```
cp /sdcard/Download/<file> <repo-path>/device-logs/
ls -la <repo-path>/device-logs/
wc -l <repo-path>/device-logs/<file>
```
Check file size and line count before committing a large log — see [[repo-safety-guardrails]] on not committing huge files without a reason, and consider whether the log belongs in a `.gitignore`d location instead of the repo proper.

**Gradle from Termux** (works for many tasks, but full Android builds may exceed what Termux can do — say so if a task looks like it needs a full Android SDK/emulator):
```
./gradlew :app:testDebugUnitTest
```

## Rules

- Give single copy-paste blocks when asked — don't split a simple sequence into multiple messages with commentary in between unless the user wants an explanation.
- Keep explanations brief unless the user asks for more detail — this is a terminal-first workflow, not a tutorial.
- When a command fails, explain the exact failure message briefly and give the fix — don't launch into a long diagnostic essay unless the quick fix doesn't work.
- Never assume a normal GitHub password will work for push auth — always point to `gh auth` or a token.

## Escalation — stop and ask when

- A command would delete files, force-push, or otherwise perform a destructive/irreversible action — confirm first, same as any other context (see [[repo-safety-guardrails]]).
- The user wants to run something that clearly needs a full Android build environment Termux can't provide — say so rather than giving a command that will just fail confusingly.

## Anti-patterns

- Don't give a multi-step tutorial when a single command block would do.
- Don't suggest committing a large raw log file straight into the main source tree without at least flagging size/location.
- Don't assume network/ADB state persists between sessions — if in doubt, include a `adb devices` check in the command block.

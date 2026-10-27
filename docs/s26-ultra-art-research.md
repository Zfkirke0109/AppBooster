# S26 Ultra ART support research

Research date: 2026-10-26 (America/Los_Angeles). Primary sources were checked
alongside the device data collected through DroidBridge. This note separates
observed hardware from implementation recommendations; it is not a performance
benchmark or a claim of successful APK installation.

## Device facts observed during this task

| Field | Live value |
| --- | --- |
| Model / device | SM-S948U / m3q |
| SoC / board | QTI SM8850 / canoe |
| OS | Android 17, SDK 37, One UI property 90000 |
| Build / patch | S948USQS4BZIG / 2026-10-05 |
| ABI / ART variant | arm64-v8a / oryon; features=default |
| Page size | 4096 bytes |
| CPU frequency policy 0 | CPUs 0-5; maximum 3,628,800 kHz |
| CPU frequency policy 6 | CPUs 6-7; maximum 4,742,400 kHz |
| Available execution | Shizuku Android shell UID 2000; root unavailable |

These are measurements from this handset, not assumptions about every S26 SKU.
The kernel documents `related_cpus` as including online and offline CPUs in a
policy, and `cpuinfo_max_freq` in kHz. Read policy membership rather than inferring
core layout from model names. [Kernel CPUFreq documentation][cpufreq]

## Implementation decisions supported by primary sources

- **Keep compilation managed by ART.** `artd` reads
  `dalvik.vm.isa.<isa>.variant` and `.features` when constructing dex2oat arguments.
  It also selects thread counts, CPU sets, and task profiles according to the
  operation priority. Preserve the observed `oryon` configuration instead of
  transplanting an S23 ISA or affinity mask. Background properties are named
  `dalvik.vm.background-dex2oat-*`, not `dalvik.vm.bg-dex2oat-*`.
  [AOSP artd source][artd]
- **Retain conservative package concurrency.** ART Service documents that
  process concurrency multiplies per-process dex2oat thread count; too many
  processes can cause memory pressure and extra context switching. One active
  package is a conservative application policy, not a measured optimum for this
  phone. Increasing it requires comparable timing, thermal, and memory evidence.
  [ART Service configuration][configuration]
- **Use advertised capabilities.** The live `cmd package help` advertises
  `speed`, `speed-profile`, `verify`, `--full`, and `-p PRIORITY_BACKGROUND`.
  AOSP's shell command source describes `--full` as including primary dex,
  secondary dex, and dependencies. Gate options on observed help; retain legacy
  fallback when an option is absent. `speed-profile` may become `verify` if no
  profile is available. The same source restricts ART shell commands to root or
  shell UID; ordinary app execution is insufficient. [ART shell source][shell]
- **Treat background priority as a resource policy.** ART defines it for work
  occurring in the background. Higher priorities may consume more resources and
  contend with other processes. Profile-guided compilation also optimizes class
  resolution/initialization; full `speed` compilation is not a universal proof of
  faster app startup. [Android 17 ART Service overview][overview]
- **Use public identity APIs where available.** `Build.SOC_MODEL` and
  `Build.SOC_MANUFACTURER` were added in API 31. Guard their use for older supported
  Android versions and tolerate unavailable values. [Android Build API][build]

## Verification scope

Add S26/Android 17 fixtures for device identity, topology, compiler capability,
and actual ART output. Keep existing S23 fixtures. A successful command exit is
not a substitute for checking the resulting compiler state. Preserve existing
battery, thermal, storage, cancellation, and Binder-output protections. Capture
only relevant logs and sanitized hardware data in committed evidence. Installing
a new build and demonstrating performance improvement remain separate runtime
gates.

[cpufreq]: https://www.kernel.org/doc/html/latest/admin-guide/pm/cpufreq.html
[artd]: https://android.googlesource.com/platform/art/+/03144b13b3489be8e5fcde799d08fcf30bd00b19/artd/artd.cc
[configuration]: https://source.android.com/docs/core/runtime/configure/art-service
[shell]: https://android.googlesource.com/platform/art/+/4b88637ae7a8fa459bcd3a22452770ec4858772f/libartservice/service/java/com/android/server/art/ArtShellCommand.java
[overview]: https://android.googlesource.com/platform/art/+/android17-release/libartservice/service/README.md
[build]: https://developer.android.com/reference/android/os/Build

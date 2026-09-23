package com.tony.appbooster.data.util

import com.tony.appbooster.domain.client.AdbShellDataSource
import com.tony.appbooster.domain.model.common.AppCompilationInfo
import com.tony.appbooster.domain.model.common.LogEntryType
import com.tony.appbooster.domain.model.common.ShellCommandSpec
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves the current compilation status for individual packages by
 * querying multiple system data sources in order of reliability and cost.
 *
 * The multi-step fallback strategy is:
 * 1. **Package metadata** — read update identity, cached only for the current scan.
 * 2. **`dumpsys package dexopt`** — single call cached for the entire run.
 * 3. **`dumpsys package <pkg>`** — per-package fallback.
 * 4. **`cmd package compile --check`** — per-package binary yes/no.
 * 5. Conservative fallback when dumpsys cannot report a compiler filter.
 *
 * @property shellDataSource Data source that executes shell commands.
 * @property logger Shared logger for diagnostic output.
 * @constructor Creates a resolver with required shell and logging dependencies.
 */
@Singleton
class CompilationInfoResolver @Inject constructor(
    private val shellDataSource: AdbShellDataSource,
    private val logger: OptimizationLogger
) {

    companion object {
        /** Common Android date format found in `dumpsys package` output. */
        private val DUMPSYS_DATE_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }

    /**
     * Cached output of `dumpsys package dexopt` for the current analysis run.
     * Avoids running an expensive global dump for every package.
     */
    private var cachedDexoptDump: String? = null

    /**
     * Per-package `dumpsys package <pkg>` cache for overlay detection.
     */
    private val cachedPackageDumps = mutableMapOf<String, String>()

    /**
     * Invalidates package metadata after compilation without inventing a compiler filter.
     *
     * @param packageName Package that was just compiled.
     */
    fun markOptimized(packageName: String) {
        cachedPackageDumps.remove(packageName)
    }

    /**
     * Resets all per-run caches. Must be called at the start of each
     * optimisation or analysis run.
     */
    fun resetCaches() {
        cachedDexoptDump = null
        cachedPackageDumps.clear()
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Public query entry point
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Queries compilation status for [packageName] through up to five
     * fallback steps, returning as soon as a definitive answer is found.
     *
     * @param packageName Package to query.
     * @param targetFilter The optimisation filter we intend to apply.
     * @return [AppCompilationInfo] with parsed compilation details.
     */
    suspend fun queryPackageCompilationInfo(
        packageName: String,
        targetFilter: String
    ): AppCompilationInfo {

        // Update identity is needed even when the global ART dump has a filter.
        // A success timestamp alone cannot prove the current mode or installed version.
        val packageOutput = packageDump(packageName)
        val lastUpdateTimeMs = packageOutput?.lineSequence()
            ?.map(String::trim)
            ?.firstOrNull { it.startsWith("lastUpdateTime=", ignoreCase = true) }
            ?.substringAfter("=")?.trim()?.let(::parseTimestamp)

        fromDexoptDump(packageName, targetFilter, lastUpdateTimeMs)?.let {
            if (it.compilerFilter != "unknown-present" || !it.needsOptimization) return it
        }

        fromPackageDumpsys(packageName, targetFilter)?.let { (info, _) ->
            if (info != null) return info
        }

        // ── Step 4: compile --check ────────────────────────────────────────────
        fromCompileCheck(packageName, lastUpdateTimeMs)?.let { return it }

        // ── Step 5: conservative fallback ─────────────────────────────────────
        return resolveCompilationInfo(packageName, null, lastUpdateTimeMs, targetFilter)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Fallback steps
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Step 2 — parses the global `dumpsys package dexopt` output (fetched once
     * per run and cached).
     */
    private suspend fun fromDexoptDump(
        packageName: String,
        targetFilter: String,
        lastUpdateTimeMs: Long?
    ): AppCompilationInfo? {
        if (cachedDexoptDump == null) {
            logger.addLogEntry(LogEntryType.ANALYZING, "Dexopt dump",
                detail = "dumpsys package dexopt (once per run)")
        } else {
            logger.addLogEntry(LogEntryType.INFO, "Dexopt dump",
                packageName = packageName, detail = "cache hit")
        }

        val dump = cachedDexoptDump ?: fetchDexoptDump()
        cachedDexoptDump = dump

        if (dump != null) {
            val filter = DexoptStatusParser.parseCompilerFilterFromDexoptDump(packageName, dump)
            if (filter != null) {
                logger.addLogEntry(LogEntryType.INFO, "Dexopt status",
                    packageName = packageName, detail = "filter=$filter")
                return resolveCompilationInfo(packageName, filter, lastUpdateTimeMs, targetFilter)
            }
        }
        return null
    }

    /**
     * Step 3 — per-package `dumpsys package <pkg>`.
     *
     * @return Pair of (resolved [AppCompilationInfo] or null, lastUpdateTimeMs or null).
     */
    private suspend fun fromPackageDumpsys(
        packageName: String,
        targetFilter: String
    ): Pair<AppCompilationInfo?, Long?>? {
        logger.addLogEntry(LogEntryType.ANALYZING, "Fallback: package dump",
            packageName = packageName, detail = "dumpsys package")

        val output = packageDump(packageName)

        if (output == null) {
            logger.addLogEntry(LogEntryType.ERROR, "Package dump failed",
                packageName = packageName)
            return null
        }

        val filter = DexoptStatusParser.parseCompilerFilterFromDexoptDump(packageName, output)
            ?.takeUnless { it == "unknown-present" }
        val lastUpdateTimeMs = output.lineSequence().map(String::trim)
            .firstOrNull { it.startsWith("lastUpdateTime=", ignoreCase = true) }
            ?.substringAfter("=")?.trim()?.let(::parseTimestamp)

        return if (filter != null) {
            logger.addLogEntry(LogEntryType.INFO, "Dexopt status",
                packageName = packageName, detail = "filter=$filter")
            resolveCompilationInfo(packageName, filter, lastUpdateTimeMs, targetFilter) to lastUpdateTimeMs
        } else {
            logger.addLogEntry(LogEntryType.INFO, "Package dump",
                packageName = packageName, detail = "no compiler filter reported")
            null to lastUpdateTimeMs
        }
    }

    /**
     * Step 4 — `cmd package compile --check`.
     */
    private suspend fun fromCompileCheck(
        packageName: String,
        lastUpdateTimeMs: Long?
    ): AppCompilationInfo? {
        logger.addLogEntry(LogEntryType.ANALYZING, "Fallback: compile check",
            packageName = packageName, detail = "cmd package compile --check")

        val checkResult = shellDataSource.executeCommandDetailed(
            ShellCommandSpec.PackageCompileCheck(packageName)
        )
        val check = checkResult.getOrNull()

        when {
            check == null -> logger.addLogEntry(LogEntryType.ERROR, "Compile check failed",
                packageName = packageName, detail = checkResult.exceptionOrNull()?.message)

            !check.isSuccess -> logger.addLogEntry(LogEntryType.INFO, "Compile check unsupported",
                packageName = packageName,
                detail = check.stderr.trim().ifEmpty { "exitCode=${check.exitCode}" })

            else -> {
                val output = check.stdout.trim()
                DexoptStatusParser.parseCompileCheckNeedsOptimization(output)?.let { needsOpt ->
                    logger.addLogEntry(LogEntryType.INFO, "Compile check result",
                        packageName = packageName,
                        detail = if (needsOpt) "needs optimization" else "already optimal")
                    return AppCompilationInfo(
                        packageName = packageName,
                        compilerFilter = null,
                        lastCompilationTimeMs = null,
                        lastUpdateTimeMs = lastUpdateTimeMs,
                        oatFileExists = false,
                        skipReason = if (needsOpt) null
                                     else AppCompilationInfo.SkipReason.AlreadyOptimal("system-check"),
                        needsOptimization = needsOpt
                    )
                }
                logger.addLogEntry(LogEntryType.INFO, "Compile check unparseable",
                    packageName = packageName, detail = output.take(120))
            }
        }
        return null
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Decision logic
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Maps a resolved [compilerFilter] (or null) to the final
     * [AppCompilationInfo.needsOptimization] flag and [AppCompilationInfo.SkipReason].
     *
     * @param packageName Package being evaluated.
     * @param compilerFilter Resolved compiler filter, or null.
     * @param lastUpdateTimeMs App's last-update timestamp, if available.
     * @param targetFilter The optimisation filter we intend to apply.
     * @return Fully resolved [AppCompilationInfo].
     */
    internal suspend fun resolveCompilationInfo(
        packageName: String,
        compilerFilter: String?,
        lastUpdateTimeMs: Long?,
        targetFilter: String
    ): AppCompilationInfo {
        val (needsOptimization, skipReason) = when {
            compilerFilter == null -> true to null

            // Overlay/RRO detection for packages present in dexopt but without filter details
            compilerFilter == "unknown-present" ->
                resolveOverlay(packageName)

            // OAT exists but exact filter unknown — conservative re-compile for speed-profile
            compilerFilter == "unknown-optimized" -> {
                val needs = targetFilter.lowercase() == "speed-profile"
                needs to if (needs) null
                         else AppCompilationInfo.SkipReason.RecentlyOptimized(0, "compiled")
            }

            isFilterOptimalForTarget(compilerFilter, targetFilter) ->
                false to AppCompilationInfo.SkipReason.RecentlyOptimized(0, compilerFilter)

            else -> true to null
        }

        return AppCompilationInfo(
            packageName = packageName,
            compilerFilter = compilerFilter,
            lastCompilationTimeMs = null,
            lastUpdateTimeMs = lastUpdateTimeMs,
            oatFileExists = compilerFilter != null,
            skipReason = skipReason,
            needsOptimization = needsOptimization
        )
    }

    /**
     * Classifies a package with `unknown-present` filter as overlay or real app.
     *
     * @return Pair of (needsOptimization, skipReason).
     */
    private suspend fun resolveOverlay(
        packageName: String
    ): Pair<Boolean, AppCompilationInfo.SkipReason?> {
        val dump = packageDump(packageName)
        val isOverlay = PackageClassifier.isOverlayLike(packageName, dump)

        return if (isOverlay) {
            logger.addLogEntry(LogEntryType.INFO, "Overlay classification",
                packageName = packageName, detail = "Confirmed as overlay/RRO")
            false to AppCompilationInfo.SkipReason.AlreadyOptimal("overlay/rro")
        } else {
            logger.addLogEntry(LogEntryType.INFO, "Overlay classification",
                packageName = packageName, detail = "Not an overlay → keep eligible")
            true to null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────────

    private suspend fun packageDump(packageName: String): String? =
        cachedPackageDumps[packageName] ?: shellDataSource
            .executeCommand(ShellCommandSpec.DumpsysPackageForPackage(packageName))
            .getOrNull()?.also { cachedPackageDumps[packageName] = it }

    /**
     * Fetches and caches the global `dumpsys package dexopt` output.
     *
     * @return Dexopt dump string on success, or null on failure.
     */
    private suspend fun fetchDexoptDump(): String? {
        val result = shellDataSource.executeCommandDetailed(ShellCommandSpec.DumpsysPackageDexopt)
        val dexopt = result.getOrNull()

        when {
            dexopt == null ->
                logger.addLogEntry(LogEntryType.ERROR, "Dexopt dump failed",
                    detail = result.exceptionOrNull()?.message)
            !dexopt.isSuccess ->
                logger.addLogEntry(LogEntryType.ERROR, "Dexopt dump failed",
                    detail = dexopt.stderr.trim().ifEmpty { "exitCode=${dexopt.exitCode}" })
        }

        return if (dexopt?.isSuccess == true) dexopt.stdout else null
    }

    /**
     * Determines if the current compiler filter is already optimal for the
     * requested target filter.
     *
     * @param currentFilter The compiler filter currently applied to the app.
     * @param targetFilter The target filter the user wants to apply.
     * @return True if re-compilation would be redundant.
     */
    internal fun isFilterOptimalForTarget(currentFilter: String, targetFilter: String): Boolean {
        val current = currentFilter.lowercase()
        val target = targetFilter.lowercase()

        return when {
            current == "everything" -> true
            current == "speed" && target in setOf("speed", "speed-profile") -> true
            current == "speed-profile" && target == "speed-profile" -> true
            else -> false
        }
    }

    /**
     * Parses a timestamp string from `dumpsys package` output.
     *
     * Supports epoch-millis numbers and the common `yyyy-MM-dd HH:mm:ss` format.
     *
     * @param timeStr Raw timestamp string.
     * @return Epoch milliseconds, or null if parsing fails.
     */
    internal fun parseTimestamp(timeStr: String): Long? {
        timeStr.toLongOrNull()?.let { return it }

        return try {
            val ldt = LocalDateTime.parse(timeStr, DUMPSYS_DATE_FORMAT)
            ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    }
}

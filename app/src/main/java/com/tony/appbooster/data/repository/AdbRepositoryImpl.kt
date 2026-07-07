package com.tony.appbooster.data.repository

import com.tony.appbooster.data.local.optimization.OptimizationStepDao
import com.tony.appbooster.data.local.optimization.OptimizationStepEntity
import com.tony.appbooster.data.local.optimization.OptimizationStepStatus
import com.tony.appbooster.data.util.CompilationInfoResolver
import com.tony.appbooster.data.util.OptimizationLogger
import com.tony.appbooster.data.util.PackageListQueryService
import com.tony.appbooster.domain.client.AdbShellDataSource
import com.tony.appbooster.domain.model.common.LogEntryType
import com.tony.appbooster.domain.model.common.LogMessageKey
import com.tony.appbooster.domain.model.common.OptimizationAnalysis
import com.tony.appbooster.domain.model.common.OptimizationPausedException
import com.tony.appbooster.domain.model.common.OptimizationProgress
import com.tony.appbooster.domain.model.common.OptimizationResult
import com.tony.appbooster.domain.model.common.OptimizationRollbackCandidate
import com.tony.appbooster.domain.model.common.PackageNameValidator
import com.tony.appbooster.domain.model.common.Resource
import com.tony.appbooster.domain.model.common.ResourceError
import com.tony.appbooster.domain.model.common.ShellCommandException
import com.tony.appbooster.domain.model.common.ShellCommandSpec
import com.tony.appbooster.domain.model.common.requireSuccess
import com.tony.appbooster.domain.model.device.blockingSummary
import com.tony.appbooster.domain.model.settings.AppOptimizationType
import com.tony.appbooster.domain.repository.AdbConnectionState
import com.tony.appbooster.domain.repository.AdbRepository
import com.tony.appbooster.domain.repository.DeviceGuardRepository
import com.tony.appbooster.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * Shizuku-based [AdbRepository] implementation that orchestrates
 * privileged shell operations for app optimisation.
 *
 * This class is a **thin orchestrator**: heavy responsibilities are
 * delegated to single-purpose helpers:
 * - [OptimizationLogger] — log state management.
 * - [PackageListQueryService] — package list queries and parsing.
 * - [CompilationInfoResolver] — per-package compilation status resolution.
 *
 * @property shellDataSource Data source that executes shell commands via Shizuku.
 * @property logger Shared structured logger for diagnostic output.
 * @property packageQuery Service for querying installed packages.
 * @property compilationResolver Service for resolving per-package compilation status.
 * @constructor Creates the repository with all required collaborators.
 */
class AdbRepositoryImpl @Inject constructor(
    private val shellDataSource: AdbShellDataSource,
    private val logger: OptimizationLogger,
    private val packageQuery: PackageListQueryService,
    private val compilationResolver: CompilationInfoResolver,
    private val optimizationStepDao: OptimizationStepDao,
    private val deviceGuardRepository: DeviceGuardRepository,
    private val settingsRepository: SettingsRepository
) : AdbRepository {

    private val _connectionState =
        MutableStateFlow<AdbConnectionState>(AdbConnectionState.Disconnected)
    override val connectionState = _connectionState.asStateFlow()

    override val commandOutput get() = logger.commandOutput
    override val logEntries get() = logger.logEntries

    private val _optimizationProgress = MutableStateFlow(OptimizationProgress())
    override val optimizationProgress = _optimizationProgress.asStateFlow()

    override fun observeRollbackCandidates() =
        optimizationStepDao.observeRollbackCandidates().map { steps ->
            steps.map { step ->
                OptimizationRollbackCandidate(
                    packageName = step.packageName,
                    beforeFilter = step.beforeFilter,
                    afterFilter = step.afterFilter,
                    optimizedAtMs = step.updatedAtMs
                )
            }
        }

    private val _optimizationAnalysis = MutableStateFlow(OptimizationAnalysis())
    override val optimizationAnalysis = _optimizationAnalysis.asStateFlow()

    private val optimizationCancelRequested = AtomicBoolean(false)
    private val analysisCancelRequested = AtomicBoolean(false)

    // ─────────────────────────────────────────────────────────────────────────────
    // Connection
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Ensures Shizuku is ready and validates shell access with a health check.
     *
     * @return [Resource.Success] when ready, or [Resource.Error] with details.
     */
    override suspend fun ensureConnected(): Resource<Unit> = runCatching {
        _connectionState.value = AdbConnectionState.Connecting
        logger.addLog("Validating Shizuku shell access...")

        val command = ShellCommandSpec.DumpsysPackageDexopt
        logger.addLog("> ${command.displayCommand}")

        val healthOutput = shellDataSource.executeCommand(command)
            .getOrThrow()
            .trim()

        logger.addLog("Shell response: ${healthOutput.take(120)}")
        _connectionState.value = AdbConnectionState.Connected
        logger.addLog("Shizuku shell session ready.")
    }.fold(
        onSuccess = { Resource.Success(Unit) },
        onFailure = { throwable ->
            _connectionState.value = AdbConnectionState.Error(
                message = throwable.message ?: "Failed to validate Shizuku connection."
            )
            logger.addLog("Error: ${throwable.message}")
            Resource.Error(
                ResourceError.LogicError(
                    errorMessage = throwable.message
                        ?: "Shizuku is not ready. Please ensure Shizuku is installed, running, and permission is granted."
                )
            )
        }
    )

    // ─────────────────────────────────────────────────────────────────────────────
    // Cancellation
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Requests cancellation of the ongoing optimisation process, if any.
     *
     * Sets a flag that is checked between package compilation commands.
     * The current step will complete before the process is marked as cancelled.
     *
     * @return [Resource.Success] if cancellation is successfully requested,
     *         or [Resource.Error] with details if the request fails.
     */
    override suspend fun cancelOptimization(): Resource<Unit> = runCatching {
        if (!_optimizationProgress.value.isRunning) {
            logger.addLog("No optimization is currently running.")
            return@runCatching
        }

        val current = _optimizationProgress.value
        _optimizationProgress.value = current.copy(
            isRunning = false,
            result = OptimizationResult.Canceled,
            currentAppPackage = "",
            progress = current.progress.coerceIn(0f, 1f)
        )

        optimizationCancelRequested.set(true)
        logger.addLog("⏹ Cancelling optimization...")
        logger.addLogEntry(LogEntryType.CANCELLED, messageKey = LogMessageKey.OPTIMIZATION_CANCELLED)
    }.fold(
        onSuccess = { Resource.Success(Unit) },
        onFailure = { Resource.Error(ResourceError.LogicError(it.message)) }
    )

    /**
     * Requests cancellation of the ongoing analysis scan, if any.
     *
     * @return [Resource.Success] if cancellation is successfully requested,
     *         or [Resource.Error] with details if the request fails.
     */
    override suspend fun cancelAnalysis(): Resource<Unit> = runCatching {
        if (!_optimizationAnalysis.value.isScanning) {
            logger.addLog("No analysis is currently running.")
            return@runCatching
        }

        _optimizationAnalysis.value = _optimizationAnalysis.value.copy(
            isScanning = false,
            currentPackage = ""
        )

        analysisCancelRequested.set(true)
        logger.addLog("⏹ Cancelling analysis...")
        logger.addLogEntry(LogEntryType.CANCELLED, messageKey = LogMessageKey.ANALYSIS_CANCELLED)
    }.fold(
        onSuccess = { Resource.Success(Unit) },
        onFailure = { Resource.Error(ResourceError.LogicError(it.message)) }
    )

    // ─────────────────────────────────────────────────────────────────────────────
    // Optimisation
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Runs the full optimisation routine: analyses which packages need
     * compilation, then compiles them one-by-one using the requested mode.
     *
     * @param mode Optimisation strategy that maps to the compile mode.
     * @return [Resource.Success] when the flow completes,
     *         or [Resource.Error] describing the failure.
     */
    override suspend fun executeOptimizationCommand(
        mode: AppOptimizationType,
        forceOptimize: Boolean
    ): Resource<Unit> {
        val compileMode = mode.value
        return runCatching {
            resetForNewRun()
            val requestedRunId = System.currentTimeMillis()
            _optimizationProgress.value = OptimizationProgress(
                runId = requestedRunId,
                isRunning = true,
                result = OptimizationResult.None
            )
            val forceLabel = if (forceOptimize) " (Force)" else ""
            logger.addLogEntry(LogEntryType.START, messageKey = LogMessageKey.STARTING_OPTIMIZATION,
                detail = "Mode: $compileMode$forceLabel")

            ensureDeviceGuardAllowsOptimization()

            val plan = findResumableOptimizationPlan(compileMode, forceOptimize)
                ?: createOptimizationPlan(mode, forceOptimize, compileMode)
                ?: run {
                    if (_optimizationProgress.value.isRunning) {
                        _optimizationProgress.value = _optimizationProgress.value.copy(
                            isRunning = false,
                            currentAppPackage = ""
                        )
                    }
                    return@runCatching
                }

            if (plan.isResumed) {
                logger.addLog("Resuming optimization run ${plan.runId}.")
                logger.addLogEntry(LogEntryType.INFO, "Resuming optimization",
                    detail = "${plan.processedCount} / ${plan.totalCount} apps")
            }

            _optimizationProgress.value = OptimizationProgress(
                runId = plan.runId,
                isRunning = true,
                result = OptimizationResult.None,
                totalCount = plan.totalCount,
                skippedCount = plan.skippedCount,
                processedCount = plan.processedCount,
                progress = if (plan.totalCount > 0) {
                    plan.processedCount.toFloat() / plan.totalCount.toFloat()
                } else {
                    0f
                }
            )

            logOptimizationStart(plan.totalCount, plan.skippedCount, compileMode)

            compilePackages(plan, compileMode)

            // Guard: never overwrite a cancellation with "Completed"
            if (wasCancelled()) return@runCatching

            finaliseCompletion(
                totalInstalled = plan.totalCount + plan.skippedCount,
                optimisedCount = plan.totalCount,
                skippedCount = plan.skippedCount,
                mode = mode
            )
        }.fold(
            onSuccess = { Resource.Success(Unit) },
            onFailure = { throwable ->
                val paused = throwable as? OptimizationPausedException
                val message = throwable.message ?: "Unknown optimization error"
                if (paused != null) {
                    logger.addLog("Optimization paused: $message")
                    logger.addLogEntry(LogEntryType.CANCELLED, "Optimization paused", detail = message)
                } else {
                    logger.addLog("Optimization failed: $message")
                    logger.addLogEntry(LogEntryType.ERROR, messageKey = LogMessageKey.OPTIMIZATION_FAILED, detail = message)
                }
                _optimizationProgress.value = _optimizationProgress.value.copy(
                    isRunning = false,
                    result = if (paused != null) {
                        OptimizationResult.Paused(message)
                    } else {
                        OptimizationResult.Failed
                    },
                    currentAppPackage = if (paused != null) {
                        ""
                    } else {
                        _optimizationProgress.value.currentAppPackage
                    },
                    progress = _optimizationProgress.value.progress.coerceIn(0f, 1f)
                )
                Resource.Error(
                    ResourceError.LogicError(
                        errorMessage = if (paused != null) {
                            "Optimization paused: $message"
                        } else {
                            "Optimization failed: $message"
                        },
                        errorCode = if (paused != null) {
                            "ADB_OPTIMIZATION_PAUSED"
                        } else {
                            "ADB_OPTIMIZATION_FAILED"
                        }
                    )
                )
            }
        )
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Analysis
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Analyses all installed apps to determine which need optimisation.
     *
     * This is a lightweight scan that checks compilation status without
     * actually performing optimisation. Results are exposed via
     * [optimizationAnalysis].
     *
     * @param mode The optimisation mode to analyse against.
     * @return [Resource] with [OptimizationAnalysis] results.
     */
    override suspend fun analyzeOptimizationStatus(
        mode: AppOptimizationType
    ): Resource<OptimizationAnalysis> = runCatching {
        analysisCancelRequested.set(false)
        logger.clearLogEntries()
        compilationResolver.resetCaches()

        _optimizationAnalysis.value = _optimizationAnalysis.value.copy(isScanning = true)
        logger.addLogEntry(LogEntryType.START, messageKey = LogMessageKey.STARTING_ANALYSIS)

        val allPackages = packageQuery.queryInstalledPackages()
        if (allPackages.isEmpty()) {
            logger.addLogEntry(LogEntryType.INFO, messageKey = LogMessageKey.NO_PACKAGES_FOUND)
            return@runCatching emptyAnalysisResult(mode)
        }

        val packagesToAnalyze = targetPackagesForMode(mode, allPackages)
        if (packagesToAnalyze.isEmpty()) {
            logger.addLogEntry(LogEntryType.INFO, "No packages selected", detail = mode.displayName())
            return@runCatching emptyAnalysisResult(mode)
        }

        logger.addLogEntry(LogEntryType.ANALYZING, messageKey = LogMessageKey.FOUND_APPS,
            detail = "${packagesToAnalyze.size} apps")

        performAnalysisScan(packagesToAnalyze, mode)
    }.fold(
        onSuccess = { Resource.Success(it) },
        onFailure = { throwable ->
            _optimizationAnalysis.value = _optimizationAnalysis.value.copy(isScanning = false)
            logger.addLogEntry(LogEntryType.ERROR, messageKey = LogMessageKey.ANALYSIS_FAILED, detail = throwable.message)
            Resource.Error(
                ResourceError.LogicError(
                    errorMessage = "Analysis failed: ${throwable.message}",
                    errorCode = "ADB_ANALYSIS_FAILED"
                )
            )
        }
    )

    // ─────────────────────────────────────────────────────────────────────────────
    // Dismissal
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Clears the last optimisation result, resetting progress and statistics.
     *
     * @return Always returns [Resource.Success] since this operation cannot fail.
     */
    override suspend fun clearOptimizationResult(): Resource<Unit> = runCatching {
        if (_optimizationProgress.value.isRunning) return@runCatching

        _optimizationProgress.value = OptimizationProgress()
    }.fold(
        onSuccess = { Resource.Success(Unit) },
        onFailure = { Resource.Error(ResourceError.LogicError(it.message)) }
    )

    override suspend fun rollbackOptimization(packageName: String): Resource<Unit> = runCatching {
        PackageNameValidator.requireValid(packageName)
        when (val connected = ensureConnected()) {
            is Resource.Success -> Unit
            is Resource.Error -> throw IllegalStateException(resourceErrorMessage(connected.data))
        }

        val command = ShellCommandSpec.PackageCompileReset(packageName)
        logger.addLog("> ${command.displayCommand}")
        shellDataSource.executeCommandDetailed(command)
            .getOrThrow()
            .requireSuccess(command.displayCommand)
        compilationResolver.resetCaches()
        logger.addLog("Rollback reset complete: $packageName")
        logger.addLogEntry(LogEntryType.SUCCESS, "Rollback reset", packageName = packageName)
    }.fold(
        onSuccess = { Resource.Success(Unit) },
        onFailure = { throwable ->
            logger.addLog("Rollback failed: ${throwable.message}")
            logger.addLogEntry(LogEntryType.ERROR, "Rollback failed", packageName = packageName, detail = throwable.message)
            Resource.Error(
                ResourceError.LogicError(
                    errorMessage = throwable.message ?: "Rollback failed",
                    errorCode = "ADB_ROLLBACK_FAILED"
                )
            )
        }
    )

    // ═══════════════════════════════════════════════════════════════════════════════
    // Private helpers
    // ═══════════════════════════════════════════════════════════════════════════════

    /** Resets cancellation flags, log entries, and per-run caches. */
    private fun resetForNewRun() {
        optimizationCancelRequested.set(false)
        logger.clearLogEntries()
        compilationResolver.resetCaches()
    }

    private suspend fun ensureDeviceGuardAllowsOptimization() {
        when (val result = deviceGuardRepository.getDeviceGuardSnapshot()) {
            is Resource.Success -> {
                val snapshot = result.data
                val battery = snapshot.batteryPercent?.let { "$it%" } ?: "unknown"
                val detail = "Battery: $battery, thermal: ${snapshot.thermalStatus.label}, " +
                    "standby: ${snapshot.standbyBucket.rawValue}"
                logger.addLog("Device guard: $detail")
                logger.addLogEntry(LogEntryType.INFO, "Device guard", detail = detail)
                if (!snapshot.canOptimize) {
                    throw OptimizationPausedException(snapshot.blockingSummary())
                }
            }
            is Resource.Error -> {
                throw IllegalStateException(
                    "Device guard check failed: ${resourceErrorMessage(result.data)}"
                )
            }
        }
    }

    private fun resourceErrorMessage(error: ResourceError): String {
        return when (error) {
            is ResourceError.LogicError -> error.errorMessage
            is ResourceError.NetworkError -> error.errorMessage
            is ResourceError.DatabaseError -> error.message
            ResourceError.SSLError -> "SSL error"
            ResourceError.UnknownError -> null
        } ?: "Unknown error"
    }

    private suspend fun findResumableOptimizationPlan(
        compileMode: String,
        forceOptimize: Boolean
    ): OptimizationRunPlan? {
        val runId = optimizationStepDao.findLatestResumableRunId(compileMode, forceOptimize)
            ?: return null
        val steps = optimizationStepDao.prepareResumedRun(runId, System.currentTimeMillis())
        if (steps.isEmpty()) return null

        val skippedCount = steps.firstOrNull()?.skippedCount ?: 0
        val processedCount = steps.count { it.status == OptimizationStepStatus.SUCCEEDED }

        return OptimizationRunPlan(
            runId = runId,
            steps = steps,
            totalCount = steps.size,
            skippedCount = skippedCount,
            processedCount = processedCount,
            isResumed = true
        )
    }

    private suspend fun createOptimizationPlan(
        mode: AppOptimizationType,
        forceOptimize: Boolean,
        compileMode: String
    ): OptimizationRunPlan? {
        val allPackages = packageQuery.queryInstalledPackages()
        if (allPackages.isEmpty()) {
            logger.addLog("No packages found for optimization.")
            logger.addLogEntry(LogEntryType.INFO, messageKey = LogMessageKey.NO_PACKAGES_FOUND)
            return null
        }
        logger.addLog("Found ${allPackages.size} installed packages.")
        val targetPackages = targetPackagesForMode(mode, allPackages)
        if (targetPackages.isEmpty()) {
            throw OptimizationPausedException(
                "No packages selected for ${mode.displayName()} mode"
            )
        }

        val packagesToOptimize: List<String>
        val skippedCount: Int

        if (forceOptimize) {
            packagesToOptimize = targetPackages
            skippedCount = 0
            logger.addLog("Force mode enabled: ${targetPackages.size} targeted packages will be compiled.")
            logger.addLogEntry(LogEntryType.INFO, messageKey = LogMessageKey.FORCE_MODE,
                detail = "${targetPackages.size} apps")
        } else {
            val (resolved, skipped) = resolvePackagesToOptimize(mode, targetPackages)
            packagesToOptimize = resolved
            skippedCount = skipped
        }

        if (packagesToOptimize.isEmpty()) {
            handleAllAlreadyOptimized(skippedCount)
            return null
        }

        val runId = System.currentTimeMillis()
        val total = packagesToOptimize.size
        val now = System.currentTimeMillis()
        val steps = packagesToOptimize.mapIndexed { index, packageName ->
            OptimizationStepEntity(
                runId = runId,
                stepIndex = index,
                totalSteps = total,
                skippedCount = skippedCount,
                packageName = packageName,
                mode = compileMode,
                forceOptimize = forceOptimize,
                createdAtMs = now,
                updatedAtMs = now
            )
        }
        optimizationStepDao.insertAll(steps)

        return OptimizationRunPlan(
            runId = runId,
            steps = optimizationStepDao.getStepsForRun(runId),
            totalCount = total,
            skippedCount = skippedCount,
            processedCount = 0,
            isResumed = false
        )
    }

    private suspend fun targetPackagesForMode(
        mode: AppOptimizationType,
        allPackages: List<String>
    ): List<String> {
        if (mode != AppOptimizationType.FULL_OPTIMIZATION) return allPackages

        val selectedPackages = when (val result = settingsRepository.getHeavyAppPackages()) {
            is Resource.Success -> result.data
            is Resource.Error -> throw IllegalStateException(resourceErrorMessage(result.data))
        }
        val filtered = allPackages.filter { it in selectedPackages }
        logger.addLog("Gaming/Heavy mode target packages: ${filtered.size} selected.")
        logger.addLogEntry(LogEntryType.INFO, "Gaming/Heavy targets", detail = "${filtered.size} apps")
        return filtered
    }

    /**
     * Determines which packages need optimisation, reusing a cached analysis
     * when valid or performing a fresh one.
     *
     * @return Pair of (packages needing optimisation, count of skipped apps).
     */
    private suspend fun resolvePackagesToOptimize(
        mode: AppOptimizationType,
        allPackages: List<String>
    ): Pair<List<String>, Int> {
        val existing = _optimizationAnalysis.value
        val analysisIsValid = existing.lastScanTimeMs != null &&
            existing.totalAppsScanned > 0 &&
            existing.mode == mode

        if (analysisIsValid) {
            logger.addLog("Using existing analysis from this session")
            logger.addLogEntry(LogEntryType.INFO, messageKey = LogMessageKey.USING_CACHED_ANALYSIS,
                detail = "${existing.appsNeedingOptimization} apps")
            val allowedPackages = allPackages.toSet()
            val packagesToOptimize = existing.packagesNeedingOptimization
                .filter { packageName -> packageName in allowedPackages }
            return packagesToOptimize to (allPackages.size - packagesToOptimize.size)
        }

        logger.addLog("Analyzing optimization status...")
        logger.addLogEntry(LogEntryType.ANALYZING, messageKey = LogMessageKey.ANALYZING_APPS,
            detail = "${allPackages.size} apps")

        val data = performAnalysisScan(allPackages, mode)
        return data.packagesNeedingOptimization to
            (data.appsAlreadyOptimized + data.appsWithNoProfile)
    }

    /** Handles the case where every package is already optimised. */
    private fun handleAllAlreadyOptimized(skippedCount: Int) {
        logger.addLog("✓ All apps are already optimized ($skippedCount apps skipped).")
        logger.addLog("No optimization needed at this time.")
        logger.addLogEntry(LogEntryType.COMPLETE, messageKey = LogMessageKey.ALL_APPS_OPTIMIZED,
            detail = "$skippedCount apps")

        _optimizationProgress.value = OptimizationProgress(
            runId = System.currentTimeMillis(),
            isRunning = false,
            result = OptimizationResult.Completed,
            skippedCount = skippedCount,
            progress = 1f
        )
    }

    /** Emits initial log messages for the compilation loop. */
    private fun logOptimizationStart(total: Int, skippedCount: Int, compileMode: String) {
        logger.addLog("Optimizing $total apps ($skippedCount already optimized, skipped).")
        logger.addLog("(Excluding ${PackageListQueryService.SELF_PACKAGE_NAME} to prevent self-crash)")
        logger.addLog("Starting compilation (Mode: $compileMode)...")
        logger.addLogEntry(LogEntryType.INFO, messageKey = LogMessageKey.MODE_INFO,
            detail = "$compileMode — $total / $skippedCount")
    }

    /**
     * Iterates over [packages] and compiles each one, checking for
     * cancellation between iterations.
     */
    private suspend fun compilePackages(
        plan: OptimizationRunPlan,
        compileMode: String
    ) {
        plan.steps.forEach { step ->
            if (step.status == OptimizationStepStatus.SUCCEEDED) return@forEach
            if (checkCancelled(plan.runId, _optimizationProgress.value.processedCount)) return

            val packageName = step.packageName
            _optimizationProgress.value = _optimizationProgress.value.copy(
                currentAppPackage = packageName
            )
            logger.addLogEntry(LogEntryType.OPTIMIZING, messageKey = LogMessageKey.OPTIMIZING_APP, packageName = packageName)

            val beforeFilter = compilationResolver
                .queryPackageCompilationInfo(packageName, compileMode)
                .compilerFilter
            optimizationStepDao.markRunning(step.id, beforeFilter, System.currentTimeMillis())

            val command = ShellCommandSpec.PackageCompile(
                packageName = packageName,
                mode = compileMode,
                force = true
            )
            logger.addLog("> ${command.displayCommand}")

            val commandResult = shellDataSource.executeCommandDetailed(command)
                .getOrElse { throwable ->
                    recordStepFailure(step.id, packageName, throwable)
                    throw throwable
                }
                .runCatchingRequireSuccess(command.displayCommand)
                .getOrElse { throwable ->
                    recordStepFailure(step.id, packageName, throwable)
                    throw throwable
                }

            logger.addLog("Success: optimized $packageName")
            logger.addLogEntry(LogEntryType.SUCCESS, messageKey = LogMessageKey.OPTIMIZED, packageName = packageName)
            compilationResolver.resetCaches()
            val afterFilter = compilationResolver
                .queryPackageCompilationInfo(packageName, compileMode)
                .compilerFilter ?: compileMode
            optimizationStepDao.markSucceeded(
                id = step.id,
                afterFilter = afterFilter,
                exitCode = commandResult.exitCode,
                stdout = commandResult.stdout,
                stderr = commandResult.stderr,
                updatedAtMs = System.currentTimeMillis()
            )
            compilationResolver.markOptimized(packageName)
            val trimmed = commandResult.stdout.trim()
            if (trimmed.isNotBlank() && !trimmed.equals("Success", ignoreCase = true)) {
                logger.addLog(trimmed)
            }

            val newCount = _optimizationProgress.value.processedCount + 1
            _optimizationProgress.value = _optimizationProgress.value.copy(
                processedCount = newCount,
                progress = newCount.toFloat() / plan.totalCount.toFloat()
            )

            if (checkCancelled(plan.runId, newCount)) return
        }
    }

    private suspend fun recordStepFailure(
        stepId: Long,
        packageName: String,
        throwable: Throwable
    ) {
        val shellFailure = throwable as? ShellCommandException
        optimizationStepDao.markFailed(
            id = stepId,
            exitCode = shellFailure?.exitCode,
            stdout = shellFailure?.stdout,
            stderr = shellFailure?.stderr ?: throwable.message,
            updatedAtMs = System.currentTimeMillis()
        )
        logger.addLog("Failure: $packageName - ${throwable.message}")
        logger.addLogEntry(LogEntryType.ERROR, messageKey = LogMessageKey.OPTIMIZATION_FAILED_APP,
            packageName = packageName, detail = throwable.message)
    }

    /** Returns true (and updates state) if cancellation was requested. */
    private suspend fun checkCancelled(runId: Long, completedCount: Int): Boolean {
        if (!optimizationCancelRequested.get() &&
            _optimizationProgress.value.result !is OptimizationResult.Canceled
        ) return false

        optimizationStepDao.markRunCanceled(runId, System.currentTimeMillis())
        logger.addLog("⏹ Optimization cancelled.")
        logger.addLogEntry(LogEntryType.CANCELLED, messageKey = LogMessageKey.OPTIMIZATION_CANCELLED,
            detail = "$completedCount apps")
        _optimizationProgress.value = _optimizationProgress.value.copy(
            isRunning = false,
            result = OptimizationResult.Canceled,
            currentAppPackage = ""
        )
        return true
    }

    /** Quick check combining both cancellation signals. */
    private fun wasCancelled(): Boolean =
        optimizationCancelRequested.get() ||
            _optimizationProgress.value.result is OptimizationResult.Canceled

    /** Updates analysis and progress state after a successful run. */
    private fun finaliseCompletion(
        totalInstalled: Int,
        optimisedCount: Int,
        skippedCount: Int,
        mode: AppOptimizationType
    ) {
        logger.addLog("✓ Optimization complete! $optimisedCount apps optimized.")
        logger.addLogEntry(LogEntryType.COMPLETE, messageKey = LogMessageKey.OPTIMIZATION_COMPLETE,
            detail = "$optimisedCount apps")

        val prevAnalysis = _optimizationAnalysis.value
        _optimizationAnalysis.value = OptimizationAnalysis(
            totalAppsScanned = totalInstalled,
            // freshly compiled this run + apps that were already optimal (skipped)
            appsNeedingOptimization = 0,
            appsAlreadyOptimized = optimisedCount + skippedCount,
            appsWithNoProfile = prevAnalysis.appsWithNoProfile,
            isScanning = false,
            lastScanTimeMs = System.currentTimeMillis(),
            mode = mode
        )

        _optimizationProgress.value = _optimizationProgress.value.copy(
            isRunning = false,
            result = OptimizationResult.Completed,
            currentAppPackage = "",
            progress = 1f
        )
    }

    /** Builds an empty analysis result for when no packages are found. */
    private fun emptyAnalysisResult(mode: AppOptimizationType): OptimizationAnalysis {
        val result = OptimizationAnalysis(
            isScanning = false,
            lastScanTimeMs = System.currentTimeMillis(),
            mode = mode
        )
        _optimizationAnalysis.value = result
        return result
    }

    /**
     * Scans all [packages] to build a full [OptimizationAnalysis].
     *
     * @param packages All installed package names.
     * @param mode The optimisation mode to analyse against.
     * @return Completed [OptimizationAnalysis].
     */
    private suspend fun performAnalysisScan(
        packages: List<String>,
        mode: AppOptimizationType
    ): OptimizationAnalysis {
        val compileMode = mode.value
        var needsOptimization = 0
        var alreadyOptimized = 0
        var noProfile = 0
        val totalApps = packages.size
        val packagesNeedingList = mutableListOf<String>()

        _optimizationAnalysis.value = _optimizationAnalysis.value.copy(
            totalAppsToScan = totalApps,
            totalAppsScanned = 0,
            currentPackage = ""
        )

        for ((index, packageName) in packages.withIndex()) {
            if (analysisCancelRequested.get()) {
                throw java.util.concurrent.CancellationException("Analysis cancelled by user")
            }

            _optimizationAnalysis.value = _optimizationAnalysis.value.copy(
                currentPackage = packageName,
                totalAppsScanned = index
            )

            val info = compilationResolver.queryPackageCompilationInfo(packageName, compileMode)

            if (info.needsOptimization) {
                needsOptimization++
                packagesNeedingList.add(packageName)
                logger.addLogEntry(LogEntryType.INFO, messageKey = LogMessageKey.NEEDS_OPTIMIZATION,
                    packageName = packageName,
                    detail = info.compilerFilter?.let { "Current: $it" })
            } else {
                classifySkippedPackage(info).let { (logType, key, filterDetail) ->
                    when (logType) {
                        LogEntryType.NO_PROFILE -> noProfile++
                        else -> alreadyOptimized++
                    }
                    logger.addLogEntry(logType, messageKey = key, packageName = packageName, detail = filterDetail)
                }
            }

            _optimizationAnalysis.value = _optimizationAnalysis.value.copy(
                totalAppsScanned = index + 1,
                appsNeedingOptimization = needsOptimization,
                appsAlreadyOptimized = alreadyOptimized,
                appsWithNoProfile = noProfile
            )
        }

        val result = OptimizationAnalysis(
            totalAppsScanned = totalApps,
            totalAppsToScan = totalApps,
            appsNeedingOptimization = needsOptimization,
            appsAlreadyOptimized = alreadyOptimized,
            appsWithNoProfile = noProfile,
            packagesNeedingOptimization = packagesNeedingList,
            isScanning = false,
            currentPackage = "",
            lastScanTimeMs = System.currentTimeMillis(),
            mode = mode
        )
        _optimizationAnalysis.value = result

        val noProfileSuffix = if (noProfile > 0) ", $noProfile no profile" else ""
        logger.addLogEntry(LogEntryType.COMPLETE, messageKey = LogMessageKey.ANALYSIS_COMPLETE,
            detail = "$needsOptimization / $alreadyOptimized$noProfileSuffix")

        return result
    }

    /**
     * Resolves a log type, message key, and optional filter detail for a skipped package.
     *
     * @param info Compilation info for the skipped package.
     * @return Triple of (log entry type, message key, optional filter detail).
     */
    private fun classifySkippedPackage(
        info: com.tony.appbooster.domain.model.common.AppCompilationInfo
    ): Triple<LogEntryType, LogMessageKey, String?> = when (val skip = info.skipReason) {
        is com.tony.appbooster.domain.model.common.AppCompilationInfo.SkipReason.RecentlyOptimized ->
            Triple(LogEntryType.SUCCESS, LogMessageKey.OPTIMIZED, skip.filter)
        is com.tony.appbooster.domain.model.common.AppCompilationInfo.SkipReason.AlreadyOptimal ->
            Triple(LogEntryType.SUCCESS, LogMessageKey.OPTIMAL, skip.filter)
        is com.tony.appbooster.domain.model.common.AppCompilationInfo.SkipReason.NoProfile ->
            Triple(LogEntryType.NO_PROFILE, LogMessageKey.NO_PROFILE_NEVER_USED, null)
        else ->
            Triple(LogEntryType.SUCCESS, LogMessageKey.ALREADY_OPTIMIZED, null)
    }

    private data class OptimizationRunPlan(
        val runId: Long,
        val steps: List<OptimizationStepEntity>,
        val totalCount: Int,
        val skippedCount: Int,
        val processedCount: Int,
        val isResumed: Boolean
    )
}

private fun com.tony.appbooster.domain.model.common.ShellCommandResult.runCatchingRequireSuccess(
    command: String
): Result<com.tony.appbooster.domain.model.common.ShellCommandResult> =
    runCatching { requireSuccess(command) }

private fun AppOptimizationType.displayName(): String = when (this) {
    AppOptimizationType.SPEED_PROFILE -> "Balanced Daily"
    AppOptimizationType.FULL_OPTIMIZATION -> "Gaming/Heavy Apps"
}

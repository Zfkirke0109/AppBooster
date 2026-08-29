package com.tony.appbooster.data.repository

import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.tony.appbooster.data.local.optimization.OptimizationStepDao
import com.tony.appbooster.data.local.optimization.OptimizationStepEntity
import com.tony.appbooster.data.local.optimization.OptimizationStepStatus
import com.tony.appbooster.data.util.CompileModeSupportParser
import com.tony.appbooster.data.util.CompileModeSupportSnapshot
import com.tony.appbooster.data.util.CompilationInfoResolver
import com.tony.appbooster.data.util.DexoptStatusParser
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
import com.tony.appbooster.domain.model.settings.OptimizationTargetScope
import com.tony.appbooster.domain.model.telemetry.OptimizationRunStatus
import com.tony.appbooster.domain.model.telemetry.OptimizationStepOutcome
import com.tony.appbooster.domain.model.telemetry.StorageSnapshot
import com.tony.appbooster.domain.model.telemetry.TelemetryExportResult
import com.tony.appbooster.domain.repository.AdbConnectionState
import com.tony.appbooster.domain.repository.AdbRepository
import com.tony.appbooster.domain.repository.DeviceGuardRepository
import com.tony.appbooster.domain.repository.OptimizationTelemetryRepository
import com.tony.appbooster.domain.repository.SettingsRepository
import com.tony.appbooster.domain.service.StorageCapacityProvider
import com.tony.appbooster.domain.service.TelemetryExporter
import kotlinx.coroutines.CancellationException
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
    private val settingsRepository: SettingsRepository,
    private val telemetryRepository: OptimizationTelemetryRepository,
    private val storageCapacityProvider: StorageCapacityProvider,
    private val telemetryExporter: TelemetryExporter
) : AdbRepository {

    companion object {
        /** Max failed package names listed by name in the end-of-run failure summary log. */
        private const val FAILED_PACKAGES_LOG_PREVIEW_COUNT = 10
        private const val VERIFICATION_SOURCE_COMMAND_FAILURE = "command-failure"
        private const val VERIFICATION_SOURCE_PACKAGE_DUMP = "cmd-package-dump"
        private const val VERIFICATION_SOURCE_VERBOSE_OUTPUT = "compile-verbose-output"
        private const val VERIFICATION_SOURCE_RESOLVER = "package-manager-resolver"
        private const val VERIFICATION_SOURCE_UNAVAILABLE = "unavailable"
    }

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
    private val runtimeIdentity = RuntimeIdentity(
        androidBuild = Build.FINGERPRINT.orEmpty(),
        artModuleVersion = System.getProperty("java.vm.version")
            ?.takeIf(String::isNotBlank)
            ?: "unknown"
    )

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
        optimizationCancelRequested.set(true)
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

        logger.addLog("⏹ Cancelling optimization...")
        logger.addLogEntry(LogEntryType.CANCELLED, messageKey = LogMessageKey.OPTIMIZATION_CANCELLED)
        if (current.runId > 0L) {
            optimizationStepDao.markRunCanceled(current.runId, System.currentTimeMillis())
            finishTelemetryRun(
                status = OptimizationRunStatus.CANCELED,
                statusMessage = "Canceled by user"
            )
        }
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
        analysisCancelRequested.set(true)
        if (!_optimizationAnalysis.value.isScanning) {
            logger.addLog("No analysis is currently running.")
            return@runCatching
        }

        _optimizationAnalysis.value = _optimizationAnalysis.value.copy(
            isScanning = false,
            currentPackage = ""
        )

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
        val compileMode = mode.requestedCompileMode
        val modeKey = mode.value
        val requestedRunId = System.currentTimeMillis()
        return runCatching {
            resetForNewRun()
            _optimizationProgress.value = OptimizationProgress(
                runId = requestedRunId,
                isRunning = true,
                result = OptimizationResult.None
            )

            val resumablePlan = findResumableOptimizationPlan(modeKey, forceOptimize)
            val activeRunId = resumablePlan?.runId ?: requestedRunId
            _optimizationProgress.value = _optimizationProgress.value.copy(runId = activeRunId)

            val startingStorage = storageCapacityProvider.snapshot()
            telemetryRepository.startOrResumeRun(
                runId = activeRunId,
                mode = mode,
                forceOptimize = forceOptimize,
                storageBefore = startingStorage
            )
            ensureStorageAllowsOptimization(startingStorage)

            val forceLabel = if (forceOptimize) " (Force)" else ""
            logger.addLogEntry(LogEntryType.START, messageKey = LogMessageKey.STARTING_OPTIMIZATION,
                detail = "Mode: ${mode.displayName} ($compileMode)$forceLabel")

            ensureDeviceGuardAllowsOptimization()
            val compileSupport = resolveCompileSupportIfNeeded(mode)
            validateCompileSupport(mode, compileSupport)

            val plan = resumablePlan
                ?: createOptimizationPlan(mode, forceOptimize, modeKey, activeRunId)
                ?: run {
                    if (_optimizationProgress.value.isRunning) {
                        _optimizationProgress.value = _optimizationProgress.value.copy(
                            isRunning = false,
                            result = OptimizationResult.Completed,
                            currentAppPackage = "",
                            progress = 1f
                        )
                    }
                    telemetryRepository.updateProgress(
                        activeRunId,
                        _optimizationProgress.value
                    )
                    finishTelemetryRun(OptimizationRunStatus.COMPLETED, "No compile steps were required")
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
                alreadyOptimizedCount = plan.alreadyOptimizedCount,
                skippedNoProfileCount = plan.skippedNoProfileCount,
                processedCount = plan.processedCount,
                optimizedSucceededCount = plan.optimizedCount,
                failedOrRefusedCount = plan.failedCount,
                unverifiedCount = plan.unverifiedCount,
                osAdjustedFilterCount = plan.osAdjustedCount,
                skippedNotApplicableCount = plan.skippedNotApplicableCount,
                verificationUnavailableCount = plan.verificationUnavailableCount,
                progress = if (plan.totalCount > 0) {
                    plan.processedCount.toFloat() / plan.totalCount.toFloat()
                } else {
                    0f
                }
            )
            telemetryRepository.updatePlanCounts(
                runId = plan.runId,
                totalTargetedCount = plan.totalCount + plan.skippedCount,
                alreadyOptimizedCount = plan.alreadyOptimizedCount,
                skippedNoProfileCount = plan.skippedNoProfileCount
            )
            telemetryRepository.updateProgress(plan.runId, _optimizationProgress.value)

            logOptimizationStart(
                total = plan.totalCount,
                alreadyOptimizedCount = plan.alreadyOptimizedCount,
                skippedNoProfileCount = plan.skippedNoProfileCount,
                compileMode = compileMode
            )

            val summary = compilePackages(plan, mode, compileSupport)

            // Guard: never overwrite a cancellation with "Completed"
            if (wasCancelled()) return@runCatching

            finaliseCompletion(
                totalInstalled = plan.totalCount + plan.skippedCount,
                summary = summary,
                alreadyOptimizedCount = plan.alreadyOptimizedCount,
                skippedNoProfileCount = plan.skippedNoProfileCount,
                mode = mode
            )
            val terminalStatus = if (_optimizationProgress.value.result is OptimizationResult.CompletedWithIssues) {
                OptimizationRunStatus.COMPLETED_WITH_ISSUES
            } else {
                OptimizationRunStatus.COMPLETED
            }
            finishTelemetryRun(terminalStatus, null)
        }.fold(
            onSuccess = { Resource.Success(Unit) },
            onFailure = { throwable ->
                if (throwable is CancellationException || wasCancelled()) {
                    // The Stop flow sets repository cancellation first, then
                    // cancels WorkManager, which aborts the in-flight shell
                    // command with a CancellationException. That exception (or
                    // any other error racing a requested cancel) must end the
                    // run as Canceled — never as Failed.
                    if (_optimizationProgress.value.result !is OptimizationResult.Canceled) {
                        logger.addLog("⏹ Optimization cancelled.")
                        logger.addLogEntry(LogEntryType.CANCELLED, messageKey = LogMessageKey.OPTIMIZATION_CANCELLED)
                    }
                    _optimizationProgress.value = _optimizationProgress.value.copy(
                        isRunning = false,
                        result = OptimizationResult.Canceled,
                        currentAppPackage = "",
                        progress = _optimizationProgress.value.progress.coerceIn(0f, 1f)
                    )
                    finishTelemetryRun(
                        status = OptimizationRunStatus.CANCELED,
                        statusMessage = "Canceled before completion"
                    )
                    val cancellation = throwable as? CancellationException
                        ?: CancellationException("Optimization canceled").apply {
                            initCause(throwable)
                        }
                    throw cancellation
                }

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
                finishTelemetryRun(
                    status = if (paused != null) {
                        OptimizationRunStatus.PAUSED
                    } else {
                        OptimizationRunStatus.FAILED
                    },
                    statusMessage = message
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
        val compileSupport = resolveCompileSupportIfNeeded(mode)
        validateCompileSupport(mode, compileSupport)

        val allPackages = packageQuery.queryInstalledPackages()
        if (allPackages.isEmpty()) {
            logger.addLogEntry(LogEntryType.INFO, messageKey = LogMessageKey.NO_PACKAGES_FOUND)
            return@runCatching emptyAnalysisResult(mode)
        }

        val packagesToAnalyze = targetPackagesForMode(mode, allPackages)
        if (packagesToAnalyze.isEmpty()) {
            logger.addLogEntry(LogEntryType.INFO, "No packages selected", detail = mode.displayName)
            return@runCatching emptyAnalysisResult(mode)
        }

        logger.addLogEntry(LogEntryType.ANALYZING, messageKey = LogMessageKey.FOUND_APPS,
            detail = "${packagesToAnalyze.size} apps")

        performAnalysisScan(packagesToAnalyze, mode)
    }.fold(
        onSuccess = { Resource.Success(it) },
        onFailure = { throwable ->
            _optimizationAnalysis.value = _optimizationAnalysis.value.copy(
                isScanning = false,
                currentPackage = ""
            )
            if (throwable is CancellationException) {
                // User-requested stop (flag check throws) or WorkManager
                // cancelled the worker mid-scan. cancelAnalysis() already
                // logged the cancellation; a cancelled scan must not be
                // reported as a failed scan, nor finalise as a successful one.
                Resource.Error(
                    ResourceError.LogicError(
                        errorMessage = "Analysis cancelled",
                        errorCode = "ADB_ANALYSIS_CANCELLED"
                    )
                )
            } else {
                logger.addLogEntry(LogEntryType.ERROR, messageKey = LogMessageKey.ANALYSIS_FAILED, detail = throwable.message)
                Resource.Error(
                    ResourceError.LogicError(
                        errorMessage = "Analysis failed: ${throwable.message}",
                        errorCode = "ADB_ANALYSIS_FAILED"
                    )
                )
            }
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

    private fun ensureStorageAllowsOptimization(snapshot: StorageSnapshot) {
        if (!snapshot.isBelowReserve) return

        val availableGiB = snapshot.availableBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        val reserveGiB = snapshot.reserveBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        throw OptimizationPausedException(
            "Storage guard: %.2f GiB available; %.2f GiB must remain free."
                .format(availableGiB, reserveGiB)
        )
    }

    private suspend fun finishTelemetryRun(
        status: OptimizationRunStatus,
        statusMessage: String?
    ) {
        val progress = _optimizationProgress.value
        if (progress.runId <= 0L) return

        val existing = runCatching { telemetryRepository.getRun(progress.runId) }
            .getOrNull() ?: return
        if (existing.status.isTerminal && status.isTerminal) {
            if (existing.exportUri == null) exportTelemetry(progress.runId)
            return
        }

        val storageAfter = runCatching { storageCapacityProvider.snapshot() }
            .getOrElse { existing.storageAfter ?: existing.storageBefore }
        val persistenceFailure = runCatching {
            telemetryRepository.finishRun(
                runId = progress.runId,
                status = status,
                statusMessage = statusMessage,
                progress = progress,
                storageAfter = storageAfter
            )
        }.exceptionOrNull()

        if (persistenceFailure != null) {
            logger.addLog("Telemetry persistence failed: ${persistenceFailure.message}")
            logger.addLogEntry(
                LogEntryType.ERROR,
                "Telemetry persistence failed",
                detail = persistenceFailure.message
            )
            return
        }

        if (status.isTerminal) exportTelemetry(progress.runId)
    }

    private suspend fun exportTelemetry(runId: Long) {
        val result = runCatching { telemetryExporter.export(runId) }
            .getOrElse { TelemetryExportResult.Failure(it.message ?: "Telemetry export failed") }
        when (result) {
            is TelemetryExportResult.Success -> {
                logger.addLog("Telemetry exported: ${result.uri}")
                logger.addLogEntry(
                    LogEntryType.INFO,
                    "Telemetry exported",
                    detail = result.uri
                )
            }
            is TelemetryExportResult.Failure -> {
                logger.addLog("Telemetry export failed: ${result.message}")
                logger.addLogEntry(
                    LogEntryType.ERROR,
                    "Telemetry export failed",
                    detail = result.message
                )
            }
        }
    }

    private suspend fun resolveCompileSupportIfNeeded(
        mode: AppOptimizationType
    ): CompileModeSupportSnapshot? {
        if (!mode.requiresRuntimeModeSupportCheck && !mode.useFullDexoptScope) return null

        val support = queryCompileModeSupport()
        val supportLog = listOf("speed-profile", "speed", "everything")
            .joinToString("\n") { candidate ->
                val status = if (candidate in support.supportedFilters) "supported" else "unsupported"
                "$candidate: $status"
            }
        logger.addLog("Compile mode support:\n$supportLog")
        logger.addLogEntry(LogEntryType.INFO, "Compile mode support", detail = supportLog)
        return support
    }

    private suspend fun queryCompileModeSupport(): CompileModeSupportSnapshot {
        val compileHelp = shellDataSource.executeCommandDetailed(ShellCommandSpec.PackageCompileHelp)
            .getOrNull()

        if (compileHelp?.isSuccess == true) {
            return CompileModeSupportParser.parse(compileHelp.stdout)
        }

        val packageHelp = shellDataSource.executeCommandDetailed(ShellCommandSpec.PackageHelp)
            .getOrThrow()
            .requireSuccess(ShellCommandSpec.PackageHelp.displayCommand)

        return CompileModeSupportParser.parse(packageHelp.stdout)
    }

    private fun validateCompileSupport(
        mode: AppOptimizationType,
        support: CompileModeSupportSnapshot?
    ) {
        if (support == null) return

        val requestedMode = mode.requestedCompileMode
        val modeSupport = support.supportFor(requestedMode)
        if (!modeSupport.isSupported) {
            throw IllegalStateException(
                "${mode.displayName}: " +
                    (modeSupport.reason ?: "Compiler filter $requestedMode is not supported.")
            )
        }

        if (mode.useFullDexoptScope && !support.supportsFullScope) {
            throw IllegalStateException(
                "${mode.displayName} requires package compile --full, but this Android build did not advertise --full."
            )
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
        val optimizedCount = steps.count {
            outcomeForStep(it) ==
                OptimizationStepOutcome.VERIFIED_REQUESTED_FILTER
        }
        val failedCount = steps.count {
            outcomeForStep(it) ==
                OptimizationStepOutcome.FAILED_OR_REFUSED
        }
        val osAdjustedCount = steps.count {
            outcomeForStep(it) ==
                OptimizationStepOutcome.OS_ADJUSTED_FILTER
        }
        val skippedNotApplicableCount = steps.count {
            outcomeForStep(it) ==
                OptimizationStepOutcome.SKIPPED_NOT_APPLICABLE
        }
        val verificationUnavailableCount = steps.count {
            outcomeForStep(it) ==
                OptimizationStepOutcome.VERIFICATION_UNAVAILABLE
        }
        val unverifiedCount = osAdjustedCount + skippedNotApplicableCount +
            verificationUnavailableCount
        val processedCount = optimizedCount + failedCount + unverifiedCount
        val existingTelemetry = telemetryRepository.getRun(runId)

        return OptimizationRunPlan(
            runId = runId,
            steps = steps,
            totalCount = steps.size,
            skippedCount = skippedCount,
            alreadyOptimizedCount = existingTelemetry?.alreadyOptimizedCount ?: skippedCount,
            skippedNoProfileCount = existingTelemetry?.skippedNoProfileCount ?: 0,
            processedCount = processedCount,
            optimizedCount = optimizedCount,
            failedCount = failedCount,
            unverifiedCount = unverifiedCount,
            osAdjustedCount = osAdjustedCount,
            skippedNotApplicableCount = skippedNotApplicableCount,
            verificationUnavailableCount = verificationUnavailableCount,
            isResumed = true
        )
    }

    private suspend fun createOptimizationPlan(
        mode: AppOptimizationType,
        forceOptimize: Boolean,
        modeKey: String,
        runId: Long
    ): OptimizationRunPlan? {
        val allPackages = packageQuery.queryInstalledPackages()
        if (allPackages.isEmpty()) {
            logger.addLog("No packages found for optimization.")
            logger.addLogEntry(LogEntryType.INFO, messageKey = LogMessageKey.NO_PACKAGES_FOUND)
            Log.i(
                OptimizationLogcatFormatter.TAG,
                OptimizationLogcatFormatter.runSummary(
                    runId = runId,
                    status = "COMPLETED",
                    targeted = 0,
                    attempted = 0,
                    success = 0,
                    skipped = 0,
                    failed = 0,
                    unverified = 0,
                    alreadyMatching = 0,
                    noProfile = 0,
                    osAdjusted = 0,
                    notApplicable = 0,
                    verificationUnavailable = 0
                )
            )
            return null
        }
        logger.addLog("Found ${allPackages.size} installed packages.")
        val targetPackages = targetPackagesForMode(mode, allPackages)
        if (targetPackages.isEmpty()) {
            throw OptimizationPausedException(
                "No packages selected for ${mode.displayName} mode"
            )
        }

        val resolution = if (forceOptimize) {
            logger.addLog("Force mode enabled: ${targetPackages.size} targeted packages will be compiled.")
            logger.addLogEntry(LogEntryType.INFO, messageKey = LogMessageKey.FORCE_MODE,
                detail = "${targetPackages.size} apps")
            PackageResolution(packagesToOptimize = targetPackages)
        } else {
            resolvePackagesToOptimize(mode, targetPackages)
        }
        val packagesToOptimize = resolution.packagesToOptimize
        val skippedCount = resolution.alreadyOptimizedCount + resolution.skippedNoProfileCount +
            resolution.osAdjustedCount + resolution.skippedNotApplicableCount

        telemetryRepository.updatePlanCounts(
            runId = runId,
            totalTargetedCount = targetPackages.size,
            alreadyOptimizedCount = resolution.alreadyOptimizedCount,
            skippedNoProfileCount = resolution.skippedNoProfileCount
        )

        if (packagesToOptimize.isEmpty()) {
            handleNoCompileNeeded(runId, resolution)
            return null
        }

        val total = packagesToOptimize.size
        val now = System.currentTimeMillis()
        val steps = packagesToOptimize.mapIndexed { index, packageName ->
            OptimizationStepEntity(
                runId = runId,
                stepIndex = index,
                totalSteps = total,
                skippedCount = skippedCount,
                packageName = packageName,
                mode = modeKey,
                forceOptimize = forceOptimize,
                requestedFilter = mode.requestedCompileMode,
                androidBuild = runtimeIdentity.androidBuild,
                artModuleVersion = runtimeIdentity.artModuleVersion,
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
            alreadyOptimizedCount = resolution.alreadyOptimizedCount,
            skippedNoProfileCount = resolution.skippedNoProfileCount,
            processedCount = 0,
            optimizedCount = 0,
            failedCount = 0,
            unverifiedCount = 0,
            osAdjustedCount = resolution.osAdjustedCount,
            skippedNotApplicableCount = resolution.skippedNotApplicableCount,
            verificationUnavailableCount = 0,
            isResumed = false
        )
    }

    private suspend fun targetPackagesForMode(
        mode: AppOptimizationType,
        allPackages: List<String>
    ): List<String> {
        return when (mode.targetScope) {
            OptimizationTargetScope.AllEligible -> allPackages
            OptimizationTargetScope.SelectedHeavyApps -> {
                val selectedPackages = when (val result = settingsRepository.getHeavyAppPackages()) {
                    is Resource.Success -> result.data
                    is Resource.Error -> throw IllegalStateException(resourceErrorMessage(result.data))
                }
                val filtered = allPackages.filter { it in selectedPackages }
                logger.addLog("Gaming/Heavy mode target packages: ${filtered.size} selected.")
                logger.addLogEntry(LogEntryType.INFO, "Gaming/Heavy targets", detail = "${filtered.size} apps")
                filtered
            }
        }
    }

    /**
     * Determines which packages need optimisation, reusing a cached analysis
     * when valid or performing a fresh one.
     *
     * @return Packages that need compilation with separate skip reasons.
     */
    private suspend fun resolvePackagesToOptimize(
        mode: AppOptimizationType,
        allPackages: List<String>
    ): PackageResolution {
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
            return PackageResolution(
                packagesToOptimize = packagesToOptimize,
                alreadyOptimizedCount = existing.appsAlreadyOptimized,
                skippedNoProfileCount = existing.appsWithNoProfile,
                osAdjustedCount = existing.osAdjustedFilterCount,
                skippedNotApplicableCount = existing.skippedNotApplicableCount
            )
        }

        logger.addLog("Analyzing optimization status...")
        logger.addLogEntry(LogEntryType.ANALYZING, messageKey = LogMessageKey.ANALYZING_APPS,
            detail = "${allPackages.size} apps")

        val data = performAnalysisScan(allPackages, mode)
        return PackageResolution(
            packagesToOptimize = data.packagesNeedingOptimization,
            alreadyOptimizedCount = data.appsAlreadyOptimized,
            skippedNoProfileCount = data.appsWithNoProfile,
            osAdjustedCount = data.osAdjustedFilterCount,
            skippedNotApplicableCount = data.skippedNotApplicableCount
        )
    }

    /** Handles the case where analysis finds no package that should be compiled. */
    private fun handleNoCompileNeeded(runId: Long, resolution: PackageResolution) {
        val skippedCount = resolution.alreadyOptimizedCount + resolution.skippedNoProfileCount +
            resolution.osAdjustedCount + resolution.skippedNotApplicableCount
        val hasClassifiedExceptions = resolution.osAdjustedCount > 0 ||
            resolution.skippedNotApplicableCount > 0
        logger.addLog(
            "No optimization needed: ${resolution.alreadyOptimizedCount} already match, " +
                "${resolution.skippedNoProfileCount} have no runtime profile."
        )
        logger.addLog("No optimization needed at this time.")
        logger.addLogEntry(LogEntryType.COMPLETE, messageKey = LogMessageKey.ALL_APPS_OPTIMIZED,
            detail = "${resolution.alreadyOptimizedCount} matching, " +
                "${resolution.skippedNoProfileCount} no profile")
        Log.i(
            OptimizationLogcatFormatter.TAG,
            OptimizationLogcatFormatter.runSummary(
                runId = runId,
                status = if (hasClassifiedExceptions) "COMPLETED_WITH_ISSUES" else "COMPLETED",
                targeted = skippedCount,
                attempted = 0,
                success = 0,
                skipped = resolution.alreadyOptimizedCount + resolution.skippedNoProfileCount +
                    resolution.skippedNotApplicableCount,
                failed = 0,
                unverified = resolution.osAdjustedCount,
                alreadyMatching = resolution.alreadyOptimizedCount,
                noProfile = resolution.skippedNoProfileCount,
                osAdjusted = resolution.osAdjustedCount,
                notApplicable = resolution.skippedNotApplicableCount,
                verificationUnavailable = 0
            )
        )

        _optimizationProgress.value = OptimizationProgress(
            runId = runId,
            isRunning = false,
            result = if (hasClassifiedExceptions) {
                OptimizationResult.CompletedWithIssues
            } else {
                OptimizationResult.Completed
            },
            skippedCount = skippedCount,
            alreadyOptimizedCount = resolution.alreadyOptimizedCount,
            skippedNoProfileCount = resolution.skippedNoProfileCount,
            osAdjustedFilterCount = resolution.osAdjustedCount,
            skippedNotApplicableCount = resolution.skippedNotApplicableCount,
            unverifiedCount = resolution.osAdjustedCount + resolution.skippedNotApplicableCount,
            totalCount = skippedCount,
            progress = 1f
        )
    }

    /** Emits initial log messages for the compilation loop. */
    private fun logOptimizationStart(
        total: Int,
        alreadyOptimizedCount: Int,
        skippedNoProfileCount: Int,
        compileMode: String
    ) {
        logger.addLog(
            "Optimizing $total apps ($alreadyOptimizedCount already matching, " +
                "$skippedNoProfileCount no profile)."
        )
        logger.addLog("(Excluding ${PackageListQueryService.SELF_PACKAGE_NAME} to prevent self-crash)")
        logger.addLog("Starting compilation (Mode: $compileMode)...")
        logger.addLogEntry(LogEntryType.INFO, messageKey = LogMessageKey.MODE_INFO,
            detail = "$compileMode — $total compile, $alreadyOptimizedCount matching, " +
                "$skippedNoProfileCount no profile")
    }

    /**
     * Iterates over [packages] and compiles each one, checking for
     * cancellation between iterations.
     *
     * A failure compiling a single package (e.g. a Samsung/Knox-protected
     * system package that rejects `cmd package compile`, or a transient
     * Shizuku/Binder error) no longer aborts the entire run. Each package is
     * compiled independently: failures are recorded against that step only,
     * and the loop continues so every remaining package is still located and
     * processed. This ensures "optimize all apps" reliably reaches every
     * installed package instead of silently stopping partway through on
     * devices with many OEM/Knox packages that may not all be compilable.
     */
    private suspend fun compilePackages(
        plan: OptimizationRunPlan,
        mode: AppOptimizationType,
        compileSupport: CompileModeSupportSnapshot?
    ): CompileRunSummary {
        val compileMode = mode.requestedCompileMode
        val failedPackages = mutableListOf<String>()
        var optimizedCount = plan.optimizedCount
        var failedCount = plan.failedCount
        var osAdjustedCount = plan.osAdjustedCount
        var skippedNotApplicableCount = plan.skippedNotApplicableCount
        var verificationUnavailableCount = plan.verificationUnavailableCount
        var processedCount = plan.processedCount
        var attemptedCount = plan.steps.count { step -> !step.displayCommand.isNullOrBlank() }

        for (step in plan.steps) {
            if (step.outcome != null ||
                step.status == OptimizationStepStatus.SUCCEEDED ||
                step.status == OptimizationStepStatus.FAILED ||
                step.status == OptimizationStepStatus.UNVERIFIED
            ) continue
            if (checkCancelled(plan.runId, processedCount)) {
                return CompileRunSummary(
                    processedCount = processedCount,
                    attemptedCount = attemptedCount,
                    optimizedCount = optimizedCount,
                    failedCount = failedCount,
                    osAdjustedCount = osAdjustedCount,
                    skippedNotApplicableCount = skippedNotApplicableCount,
                    verificationUnavailableCount = verificationUnavailableCount
                )
            }

            val packageName = step.packageName
            _optimizationProgress.value = _optimizationProgress.value.copy(
                currentAppPackage = packageName
            )
            logger.addLogEntry(LogEntryType.OPTIMIZING, messageKey = LogMessageKey.OPTIMIZING_APP, packageName = packageName)

            val beforeInfo = compilationResolver.queryPackageCompilationInfo(packageName, compileMode)
            val beforeFilter = beforeInfo.compilerFilter

            val packageDump = try {
                shellDataSource
                    .executeCommandDetailed(ShellCommandSpec.PackageDump(packageName))
                    .getOrNull()
                    ?.takeIf { it.isSuccess }
                    ?.stdout
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // HAS_CODE is an opportunistic preflight. An unavailable dump
                // must not prevent the bounded compile command from running.
                null
            }
            if (packageDump != null && DexoptStatusParser.parsePackageHasCode(packageDump) == false) {
                val reason = "Package contains no executable DEX code; compile not applicable."
                val now = System.currentTimeMillis()
                optimizationStepDao.markClassifiedResult(
                    id = step.id,
                    status = OptimizationStepStatus.SKIPPED_NOT_APPLICABLE,
                    afterFilter = beforeFilter,
                    exitCode = 0,
                    stdout = packageDump,
                    stderr = reason,
                    updatedAtMs = now
                )
                recordOutcome(
                    step = step,
                    outcome = OptimizationStepOutcome.SKIPPED_NOT_APPLICABLE,
                    art = DexoptStatusParser.ArtCompileResult(
                        actualCompilerFilter = beforeFilter,
                        status = "SKIPPED",
                        finalStatus = "SKIPPED",
                        sizeBytes = null,
                        sizeBeforeBytes = null
                    ),
                    packageLastUpdateTimeMs = beforeInfo.lastUpdateTimeMs,
                    stableOsAdjusted = true,
                    updatedAtMs = now
                )
                logger.addLog("Not applicable: $packageName - no executable DEX code")
                logger.addLogEntry(
                    LogEntryType.INFO,
                    "Package not applicable",
                    packageName = packageName,
                    detail = reason
                )
                Log.i(
                    OptimizationLogcatFormatter.TAG,
                    OptimizationLogcatFormatter.packageVerification(
                        runId = plan.runId,
                        packageName = packageName,
                        requestedFilter = compileMode,
                        beforeFilter = beforeFilter,
                        actualFilter = beforeFilter,
                        outcome = OptimizationStepOutcome.SKIPPED_NOT_APPLICABLE,
                        source = "package-dump-preflight",
                        attempted = false,
                        exitCode = 0,
                        durationMs = 0L
                    )
                )
                skippedNotApplicableCount++
                processedCount++
                updateCompileProgress(
                    plan,
                    processedCount,
                    optimizedCount,
                    failedCount,
                    osAdjustedCount,
                    skippedNotApplicableCount,
                    verificationUnavailableCount
                )
                continue
            }

            val command = ShellCommandSpec.PackageCompile(
                packageName = packageName,
                mode = compileMode,
                force = true,
                full = mode.useFullDexoptScope,
                verbose = compileSupport?.supportsVerboseCompile == true
            )
            val storageBefore = storageCapacityProvider.snapshot()
            ensureStorageAllowsOptimization(storageBefore)
            optimizationStepDao.markRunning(step.id, beforeFilter, System.currentTimeMillis())
            telemetryRepository.recordStepStarted(
                stepId = step.id,
                displayCommand = command.displayCommand,
                storageBefore = storageBefore
            )
            attemptedCount++
            logger.addLog("> ${command.displayCommand}")

            val commandStartedAtMs = SystemClock.elapsedRealtime()
            val commandAttempt = shellDataSource.executeCommandDetailed(command)
                .mapCatching { it.requireSuccess(command.displayCommand) }
            val commandDurationMs = (SystemClock.elapsedRealtime() - commandStartedAtMs)
                .coerceAtLeast(0L)
            val storageAfter = storageCapacityProvider.snapshot()
            if (commandAttempt.isFailure) {
                val throwable = commandAttempt.exceptionOrNull()
                    ?: IllegalStateException("Compile command failed")
                // The shell layer wraps execution in runCatching, so a worker
                // cancellation can surface as Result.failure(CancellationException).
                // That is not a package failure — rethrow so the run-level
                // handler records the Canceled outcome.
                if (throwable is CancellationException) throw throwable
                recordStepFailure(step.id, packageName, throwable)
                recordOutcome(
                    step = step,
                    outcome = OptimizationStepOutcome.FAILED_OR_REFUSED,
                    art = DexoptStatusParser.ArtCompileResult(null, null, null, null, null),
                    packageLastUpdateTimeMs = beforeInfo.lastUpdateTimeMs,
                    stableOsAdjusted = false,
                    updatedAtMs = System.currentTimeMillis()
                )
                telemetryRepository.recordStepFinished(
                    stepId = step.id,
                    durationMs = commandDurationMs,
                    storageAfter = storageAfter,
                    verificationSource = VERIFICATION_SOURCE_COMMAND_FAILURE
                )
                Log.i(
                    OptimizationLogcatFormatter.TAG,
                    OptimizationLogcatFormatter.packageVerification(
                        runId = plan.runId,
                        packageName = packageName,
                        requestedFilter = compileMode,
                        beforeFilter = beforeFilter,
                        actualFilter = null,
                        outcome = OptimizationStepOutcome.FAILED_OR_REFUSED,
                        source = VERIFICATION_SOURCE_COMMAND_FAILURE,
                        attempted = true,
                        exitCode = (throwable as? ShellCommandException)?.exitCode,
                        durationMs = commandDurationMs
                    )
                )
                failedPackages += packageName
                failedCount++
                processedCount++
                updateCompileProgress(
                    plan,
                    processedCount,
                    optimizedCount,
                    failedCount,
                    osAdjustedCount,
                    skippedNotApplicableCount,
                    verificationUnavailableCount
                )
                continue
            }
            val commandResult = commandAttempt.getOrThrow()

            val verification = verifyPackageCompile(packageName, mode, commandResult)
            val resultRecordedAtMs = System.currentTimeMillis()
            when (verification.outcome) {
                OptimizationStepOutcome.VERIFIED_REQUESTED_FILTER -> {
                    logger.addLog("Verified: optimized $packageName (${verification.filter ?: compileMode})")
                    logger.addLogEntry(
                        LogEntryType.SUCCESS,
                        messageKey = LogMessageKey.OPTIMIZED,
                        packageName = packageName,
                        detail = verification.filter
                    )
                    optimizationStepDao.markSucceeded(
                        id = step.id,
                        afterFilter = verification.filter,
                        exitCode = commandResult.exitCode,
                        stdout = commandResult.stdout,
                        stderr = commandResult.stderr,
                        updatedAtMs = resultRecordedAtMs
                    )
                    compilationResolver.markOptimized(packageName)
                    optimizedCount++
                }
                OptimizationStepOutcome.OS_ADJUSTED_FILTER -> {
                    optimizationStepDao.markClassifiedResult(
                        id = step.id,
                        status = OptimizationStepStatus.OS_ADJUSTED,
                        afterFilter = verification.filter,
                        exitCode = commandResult.exitCode,
                        stdout = commandResult.stdout,
                        stderr = commandResult.stderr.ifBlank { verification.reason },
                        updatedAtMs = resultRecordedAtMs
                    )
                    logger.addLog("Android adjusted: $packageName - ${verification.reason}")
                    logger.addLogEntry(
                        LogEntryType.INFO,
                        "Android adjusted compiler filter",
                        packageName = packageName,
                        detail = verification.reason
                    )
                    osAdjustedCount++
                }
                OptimizationStepOutcome.SKIPPED_NOT_APPLICABLE -> {
                    optimizationStepDao.markClassifiedResult(
                        id = step.id,
                        status = OptimizationStepStatus.SKIPPED_NOT_APPLICABLE,
                        afterFilter = verification.filter,
                        exitCode = commandResult.exitCode,
                        stdout = commandResult.stdout,
                        stderr = commandResult.stderr.ifBlank { verification.reason },
                        updatedAtMs = resultRecordedAtMs
                    )
                    logger.addLog("Not applicable: $packageName - ${verification.reason}")
                    logger.addLogEntry(
                        LogEntryType.INFO,
                        "Package not applicable",
                        packageName = packageName,
                        detail = verification.reason
                    )
                    skippedNotApplicableCount++
                }
                OptimizationStepOutcome.VERIFICATION_UNAVAILABLE -> {
                    optimizationStepDao.markClassifiedResult(
                        id = step.id,
                        status = OptimizationStepStatus.VERIFICATION_UNAVAILABLE,
                        afterFilter = verification.filter,
                        exitCode = commandResult.exitCode,
                        stdout = commandResult.stdout,
                        stderr = commandResult.stderr.ifBlank { verification.reason },
                        updatedAtMs = resultRecordedAtMs
                    )
                    logger.addLog("Verification unavailable: $packageName - ${verification.reason}")
                    logger.addLogEntry(
                        LogEntryType.ERROR,
                        "Verification unavailable",
                        packageName = packageName,
                        detail = verification.reason
                    )
                    verificationUnavailableCount++
                }
                OptimizationStepOutcome.FAILED_OR_REFUSED -> error(
                    "Nonzero compile results must be handled before ART verification"
                )
            }
            recordOutcome(
                step = step,
                outcome = verification.outcome,
                art = verification.art,
                packageLastUpdateTimeMs = beforeInfo.lastUpdateTimeMs,
                stableOsAdjusted = verification.stableOsAdjusted,
                updatedAtMs = resultRecordedAtMs
            )
            Log.i(
                OptimizationLogcatFormatter.TAG,
                OptimizationLogcatFormatter.packageVerification(
                    runId = plan.runId,
                    packageName = packageName,
                    requestedFilter = compileMode,
                    beforeFilter = beforeFilter,
                    actualFilter = verification.filter,
                    outcome = verification.outcome,
                    source = verification.source,
                    attempted = true,
                    exitCode = commandResult.exitCode,
                    durationMs = commandDurationMs
                )
            )
            telemetryRepository.recordStepFinished(
                stepId = step.id,
                durationMs = commandDurationMs,
                storageAfter = storageAfter,
                verificationSource = verification.source
            )
            val trimmed = commandResult.stdout.trim()
            if (trimmed.isNotBlank() && !trimmed.equals("Success", ignoreCase = true)) {
                logger.addLog(trimmed)
            }

            processedCount++
            updateCompileProgress(
                plan,
                processedCount,
                optimizedCount,
                failedCount,
                osAdjustedCount,
                skippedNotApplicableCount,
                verificationUnavailableCount
            )

            if (checkCancelled(plan.runId, processedCount)) {
                return CompileRunSummary(
                    processedCount = processedCount,
                    attemptedCount = attemptedCount,
                    optimizedCount = optimizedCount,
                    failedCount = failedCount,
                    osAdjustedCount = osAdjustedCount,
                    skippedNotApplicableCount = skippedNotApplicableCount,
                    verificationUnavailableCount = verificationUnavailableCount
                )
            }
        }

        if (failedPackages.isNotEmpty()) {
            val failedCount = failedPackages.size
            val namesPreview = failedPackages.take(FAILED_PACKAGES_LOG_PREVIEW_COUNT).joinToString(", ")
            val remaining = failedCount - minOf(failedCount, FAILED_PACKAGES_LOG_PREVIEW_COUNT)
            val namesSummary = if (remaining > 0) "$namesPreview, and $remaining more" else namesPreview
            logger.addLog("⚠ $failedCount app(s) failed to optimize and were skipped ($namesSummary); the rest were processed normally.")
            logger.addLogEntry(LogEntryType.ERROR, messageKey = LogMessageKey.OPTIMIZATION_FAILED_APP,
                detail = "$failedCount app(s) skipped due to errors: $namesSummary")
        }

        return CompileRunSummary(
            processedCount = processedCount,
            attemptedCount = attemptedCount,
            optimizedCount = optimizedCount,
            failedCount = failedCount,
            osAdjustedCount = osAdjustedCount,
            skippedNotApplicableCount = skippedNotApplicableCount,
            verificationUnavailableCount = verificationUnavailableCount
        )
    }

    private suspend fun updateCompileProgress(
        plan: OptimizationRunPlan,
        processedCount: Int,
        optimizedCount: Int,
        failedCount: Int,
        osAdjustedCount: Int,
        skippedNotApplicableCount: Int,
        verificationUnavailableCount: Int
    ) {
        val unverifiedCount = osAdjustedCount + skippedNotApplicableCount +
            verificationUnavailableCount
        _optimizationProgress.value = _optimizationProgress.value.copy(
            processedCount = processedCount,
            optimizedSucceededCount = optimizedCount,
            failedOrRefusedCount = failedCount,
            unverifiedCount = unverifiedCount,
            osAdjustedFilterCount = osAdjustedCount,
            skippedNotApplicableCount = skippedNotApplicableCount,
            verificationUnavailableCount = verificationUnavailableCount,
            progress = processedCount.toFloat() / plan.totalCount.toFloat()
        )
        telemetryRepository.updateProgress(plan.runId, _optimizationProgress.value)
    }

    private suspend fun verifyPackageCompile(
        packageName: String,
        mode: AppOptimizationType,
        commandResult: com.tony.appbooster.domain.model.common.ShellCommandResult
    ): PackageCompileVerification {
        val combinedOutput = "${commandResult.stdout}\n${commandResult.stderr}"
        val parsedArt = DexoptStatusParser.parseArtCompileResult(combinedOutput)
        val verboseFilter = parsedArt.actualCompilerFilter
            ?: DexoptStatusParser.parseCompilerFilterFromOutput(combinedOutput)

        val dumpFilter = runCatching {
            shellDataSource.executeCommandDetailed(ShellCommandSpec.PackageDump(packageName))
                .getOrNull()
                ?.takeIf { it.isSuccess }
                ?.let { result -> DexoptStatusParser.parseCompilerFilterFromOutput(result.stdout) }
        }.getOrNull()

        val resolverFilter = if (dumpFilter == null && verboseFilter == null) {
            // Package state changed after compilation. Refresh resolver caches only
            // when direct package/verbose evidence could not verify the result.
            compilationResolver.resetCaches()
            compilationResolver
                .queryPackageCompilationInfo(packageName, mode.requestedCompileMode)
                .compilerFilter
        } else {
            null
        }

        val actualFilter = dumpFilter ?: verboseFilter ?: resolverFilter
        val skipped = parsedArt.finalStatus == "SKIPPED" || parsedArt.status == "SKIPPED"
        val outcome = when {
            skipped -> OptimizationStepOutcome.SKIPPED_NOT_APPLICABLE
            actualFilter == null -> OptimizationStepOutcome.VERIFICATION_UNAVAILABLE
            DexoptStatusParser.isRequestedFilterSatisfied(
                mode.requestedCompileMode,
                actualFilter
            ) -> OptimizationStepOutcome.VERIFIED_REQUESTED_FILTER
            else -> OptimizationStepOutcome.OS_ADJUSTED_FILTER
        }
        val source = when {
            dumpFilter != null -> VERIFICATION_SOURCE_PACKAGE_DUMP
            verboseFilter != null -> VERIFICATION_SOURCE_VERBOSE_OUTPUT
            resolverFilter != null -> VERIFICATION_SOURCE_RESOLVER
            else -> VERIFICATION_SOURCE_UNAVAILABLE
        }

        return PackageCompileVerification(
            outcome = outcome,
            filter = actualFilter,
            source = source,
            art = parsedArt.copy(actualCompilerFilter = actualFilter),
            stableOsAdjusted = outcome == OptimizationStepOutcome.OS_ADJUSTED_FILTER ||
                outcome == OptimizationStepOutcome.SKIPPED_NOT_APPLICABLE,
            reason = when {
                outcome == OptimizationStepOutcome.VERIFIED_REQUESTED_FILTER -> "Verified"
                outcome == OptimizationStepOutcome.SKIPPED_NOT_APPLICABLE ->
                    "ART skipped this package as not applicable."
                outcome == OptimizationStepOutcome.VERIFICATION_UNAVAILABLE ->
                    "Post-run ART state was unavailable."
                else -> "Requested ${mode.requestedCompileMode}; Android retained $actualFilter. " +
                    "The compile command completed without failure."
            }
        )
    }

    private suspend fun recordOutcome(
        step: OptimizationStepEntity,
        outcome: OptimizationStepOutcome,
        art: DexoptStatusParser.ArtCompileResult,
        packageLastUpdateTimeMs: Long?,
        stableOsAdjusted: Boolean,
        updatedAtMs: Long
    ) {
        optimizationStepDao.recordOutcome(
            id = step.id,
            outcome = outcome.name,
            requestedFilter = step.requestedFilter ?: when (step.mode) {
                AppOptimizationType.SPEED_PROFILE.value -> "speed-profile"
                else -> "speed"
            },
            artStatus = art.status,
            artFinalStatus = art.finalStatus,
            artSizeBytes = art.sizeBytes,
            artSizeBeforeBytes = art.sizeBeforeBytes,
            androidBuild = runtimeIdentity.androidBuild,
            artModuleVersion = runtimeIdentity.artModuleVersion,
            packageLastUpdateTimeMs = packageLastUpdateTimeMs,
            stableOsAdjusted = stableOsAdjusted,
            updatedAtMs = updatedAtMs
        )
    }

    /** Reads the v3 outcome, with a defensive mapping for interrupted v2-era rows. */
    private fun outcomeForStep(step: OptimizationStepEntity): OptimizationStepOutcome? =
        OptimizationStepOutcome.fromStoredValue(step.outcome) ?: when (step.status) {
            OptimizationStepStatus.SUCCEEDED -> OptimizationStepOutcome.VERIFIED_REQUESTED_FILTER
            OptimizationStepStatus.FAILED -> OptimizationStepOutcome.FAILED_OR_REFUSED
            OptimizationStepStatus.UNVERIFIED,
            OptimizationStepStatus.VERIFICATION_UNAVAILABLE ->
                OptimizationStepOutcome.VERIFICATION_UNAVAILABLE
            OptimizationStepStatus.OS_ADJUSTED -> OptimizationStepOutcome.OS_ADJUSTED_FILTER
            OptimizationStepStatus.SKIPPED_NOT_APPLICABLE ->
                OptimizationStepOutcome.SKIPPED_NOT_APPLICABLE
            else -> null
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
        if (_optimizationProgress.value.result is OptimizationResult.Canceled) return true
        if (!optimizationCancelRequested.get()) return false

        optimizationStepDao.markRunCanceled(runId, System.currentTimeMillis())
        logger.addLog("⏹ Optimization cancelled.")
        logger.addLogEntry(LogEntryType.CANCELLED, messageKey = LogMessageKey.OPTIMIZATION_CANCELLED,
            detail = "$completedCount apps")
        _optimizationProgress.value = _optimizationProgress.value.copy(
            isRunning = false,
            result = OptimizationResult.Canceled,
            currentAppPackage = ""
        )
        finishTelemetryRun(
            status = OptimizationRunStatus.CANCELED,
            statusMessage = "Canceled before completion"
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
        summary: CompileRunSummary,
        alreadyOptimizedCount: Int,
        skippedNoProfileCount: Int,
        mode: AppOptimizationType
    ) {
        val unverifiedCount = summary.osAdjustedCount + summary.skippedNotApplicableCount +
            summary.verificationUnavailableCount
        val hasIssues = summary.failedCount > 0 || summary.osAdjustedCount > 0 ||
            summary.skippedNotApplicableCount > 0 || summary.verificationUnavailableCount > 0
        val skippedCount = alreadyOptimizedCount + skippedNoProfileCount +
            summary.skippedNotApplicableCount
        val explicitlyUnverifiedCount = summary.osAdjustedCount +
            summary.verificationUnavailableCount
        val terminalStatus = if (hasIssues) "COMPLETED_WITH_ISSUES" else "COMPLETED"
        val completionDetail = buildString {
            append("${summary.optimizedCount} verified optimized")
            if (alreadyOptimizedCount > 0) append(", $alreadyOptimizedCount already matching")
            if (skippedNoProfileCount > 0) append(", $skippedNoProfileCount no profile")
            if (summary.failedCount > 0) append(", ${summary.failedCount} failed/refused")
            if (summary.osAdjustedCount > 0) append(", ${summary.osAdjustedCount} Android-adjusted")
            if (summary.skippedNotApplicableCount > 0) {
                append(", ${summary.skippedNotApplicableCount} not applicable")
            }
            if (summary.verificationUnavailableCount > 0) {
                append(", ${summary.verificationUnavailableCount} verification unavailable")
            }
        }
        logger.addLog(
            if (hasIssues) {
                "Optimization finished with issues: $completionDetail."
            } else {
                "✓ Optimization complete: $completionDetail."
            }
        )
        logger.addLogEntry(LogEntryType.COMPLETE, messageKey = LogMessageKey.OPTIMIZATION_COMPLETE,
            detail = completionDetail)
        Log.i(
            OptimizationLogcatFormatter.TAG,
            OptimizationLogcatFormatter.runSummary(
                runId = _optimizationProgress.value.runId,
                status = terminalStatus,
                targeted = totalInstalled,
                attempted = summary.attemptedCount,
                success = summary.optimizedCount,
                skipped = skippedCount,
                failed = summary.failedCount,
                unverified = explicitlyUnverifiedCount,
                alreadyMatching = alreadyOptimizedCount,
                noProfile = skippedNoProfileCount,
                osAdjusted = summary.osAdjustedCount,
                notApplicable = summary.skippedNotApplicableCount,
                verificationUnavailable = summary.verificationUnavailableCount
            )
        )

        _optimizationAnalysis.value = OptimizationAnalysis(
            totalAppsScanned = totalInstalled,
            appsNeedingOptimization = 0,
            appsAlreadyOptimized = summary.optimizedCount + alreadyOptimizedCount,
            appsWithNoProfile = skippedNoProfileCount,
            failedOrRefusedCount = summary.failedCount,
            unverifiedCount = unverifiedCount,
            osAdjustedFilterCount = summary.osAdjustedCount,
            skippedNotApplicableCount = summary.skippedNotApplicableCount,
            verificationUnavailableCount = summary.verificationUnavailableCount,
            isScanning = false,
            lastScanTimeMs = System.currentTimeMillis(),
            mode = mode
        )

        _optimizationProgress.value = _optimizationProgress.value.copy(
            isRunning = false,
            result = if (hasIssues) OptimizationResult.CompletedWithIssues else OptimizationResult.Completed,
            currentAppPackage = "",
            progress = 1f,
            optimizedSucceededCount = summary.optimizedCount,
            failedOrRefusedCount = summary.failedCount,
            unverifiedCount = unverifiedCount,
            osAdjustedFilterCount = summary.osAdjustedCount,
            skippedNotApplicableCount = summary.skippedNotApplicableCount,
            verificationUnavailableCount = summary.verificationUnavailableCount
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
        val compileMode = mode.requestedCompileMode
        var needsOptimization = 0
        var alreadyOptimized = 0
        var noProfile = 0
        var osAdjusted = 0
        var skippedNotApplicable = 0
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
            val cachedAdjusted = optimizationStepDao.findStableAdjustedOutcome(
                packageName = packageName,
                requestedFilter = compileMode,
                androidBuild = runtimeIdentity.androidBuild,
                artModuleVersion = runtimeIdentity.artModuleVersion,
                packageLastUpdateTimeMs = info.lastUpdateTimeMs
            )

            if (cachedAdjusted != null) {
                when (OptimizationStepOutcome.fromStoredValue(cachedAdjusted.outcome)) {
                    OptimizationStepOutcome.OS_ADJUSTED_FILTER -> osAdjusted++
                    OptimizationStepOutcome.SKIPPED_NOT_APPLICABLE -> skippedNotApplicable++
                    else -> Unit
                }
                logger.addLogEntry(
                    LogEntryType.INFO,
                    "Stable Android outcome",
                    packageName = packageName,
                    detail = cachedAdjusted.outcome
                )
            } else if (info.needsOptimization) {
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
                appsWithNoProfile = noProfile,
                osAdjustedFilterCount = osAdjusted,
                skippedNotApplicableCount = skippedNotApplicable
            )
        }

        val result = OptimizationAnalysis(
            totalAppsScanned = totalApps,
            totalAppsToScan = totalApps,
            appsNeedingOptimization = needsOptimization,
            appsAlreadyOptimized = alreadyOptimized,
            appsWithNoProfile = noProfile,
            osAdjustedFilterCount = osAdjusted,
            skippedNotApplicableCount = skippedNotApplicable,
            unverifiedCount = osAdjusted + skippedNotApplicable,
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
        val alreadyOptimizedCount: Int,
        val skippedNoProfileCount: Int,
        val processedCount: Int,
        val optimizedCount: Int,
        val failedCount: Int,
        val unverifiedCount: Int,
        val osAdjustedCount: Int,
        val skippedNotApplicableCount: Int,
        val verificationUnavailableCount: Int,
        val isResumed: Boolean
    )

    private data class PackageResolution(
        val packagesToOptimize: List<String>,
        val alreadyOptimizedCount: Int = 0,
        val skippedNoProfileCount: Int = 0,
        val osAdjustedCount: Int = 0,
        val skippedNotApplicableCount: Int = 0
    )

    private data class CompileRunSummary(
        val processedCount: Int,
        val attemptedCount: Int,
        val optimizedCount: Int,
        val failedCount: Int,
        val osAdjustedCount: Int,
        val skippedNotApplicableCount: Int,
        val verificationUnavailableCount: Int
    )

    private data class PackageCompileVerification(
        val outcome: OptimizationStepOutcome,
        val filter: String?,
        val source: String,
        val art: DexoptStatusParser.ArtCompileResult,
        val stableOsAdjusted: Boolean,
        val reason: String
    )

    private data class RuntimeIdentity(
        val androidBuild: String,
        val artModuleVersion: String
    )
}

package com.tony.appbooster.data.telemetry

import android.os.Build
import com.tony.appbooster.BuildConfig
import com.tony.appbooster.data.local.optimization.OptimizationRunDao
import com.tony.appbooster.data.local.optimization.OptimizationRunEntity
import com.tony.appbooster.data.local.optimization.OptimizationStepDao
import com.tony.appbooster.data.local.optimization.OptimizationStepEntity
import com.tony.appbooster.domain.model.common.OptimizationProgress
import com.tony.appbooster.domain.model.settings.AppOptimizationType
import com.tony.appbooster.domain.model.telemetry.OptimizationRunStatus
import com.tony.appbooster.domain.model.telemetry.OptimizationRunTelemetry
import com.tony.appbooster.domain.model.telemetry.OptimizationStepTelemetry
import com.tony.appbooster.domain.model.telemetry.StorageSnapshot
import com.tony.appbooster.domain.model.telemetry.TelemetryExportResult
import com.tony.appbooster.domain.repository.OptimizationTelemetryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OptimizationTelemetryRepositoryImpl @Inject constructor(
    private val runDao: OptimizationRunDao,
    private val stepDao: OptimizationStepDao
) : OptimizationTelemetryRepository {
    override fun observeLatestRun(): Flow<OptimizationRunTelemetry?> =
        runDao.observeLatestRun().map { entity -> entity?.toDomain() }

    override suspend fun getRun(runId: Long): OptimizationRunTelemetry? =
        runDao.getRun(runId)?.toDomain()

    override suspend fun getLatestRun(): OptimizationRunTelemetry? =
        runDao.getLatestRun()?.toDomain()

    override suspend fun getSteps(runId: Long): List<OptimizationStepTelemetry> =
        stepDao.getStepsForRun(runId).map(OptimizationStepEntity::toDomain)

    override suspend fun startOrResumeRun(
        runId: Long,
        mode: AppOptimizationType,
        forceOptimize: Boolean,
        storageBefore: StorageSnapshot
    ) {
        val existing = runDao.getRun(runId)
        val run = existing?.copy(
            status = OptimizationRunStatus.RUNNING.name,
            statusMessage = null,
            finishedAtMs = null,
            exportError = null
        ) ?: OptimizationRunEntity(
            runId = runId,
            modeKey = mode.value,
            requestedCompilerFilter = mode.requestedCompileMode,
            fullDexoptScope = mode.useFullDexoptScope,
            forceOptimize = forceOptimize,
            status = OptimizationRunStatus.RUNNING.name,
            startedAtMs = System.currentTimeMillis(),
            storageTotalBeforeBytes = storageBefore.totalBytes,
            storageAvailableBeforeBytes = storageBefore.availableBytes,
            storageReserveBytes = storageBefore.reserveBytes,
            storageCapturedBeforeAtMs = storageBefore.capturedAtMs,
            appVersionName = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE.toLong(),
            deviceManufacturer = Build.MANUFACTURER.orEmpty(),
            deviceModel = Build.MODEL.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT,
            buildFingerprint = Build.FINGERPRINT.orEmpty(),
            threadPolicy = OptimizationRunTelemetry.PACKAGE_MANAGER_THREAD_POLICY
        )
        runDao.upsert(run)
    }

    override suspend fun updatePlanCounts(
        runId: Long,
        totalTargetedCount: Int,
        alreadyOptimizedCount: Int,
        skippedNoProfileCount: Int
    ) {
        runDao.updatePlanCounts(
            runId = runId,
            totalTargetedCount = totalTargetedCount,
            alreadyOptimizedCount = alreadyOptimizedCount,
            skippedNoProfileCount = skippedNoProfileCount
        )
    }

    override suspend fun updateProgress(runId: Long, progress: OptimizationProgress) {
        runDao.updateProgress(
            runId = runId,
            processedCount = progress.processedCount,
            optimizedSucceededCount = progress.optimizedSucceededCount,
            alreadyOptimizedCount = progress.alreadyOptimizedCount,
            skippedNoProfileCount = progress.skippedNoProfileCount,
            failedOrRefusedCount = progress.failedOrRefusedCount,
            unverifiedCount = progress.unverifiedCount
        )
    }

    override suspend fun recordStepStarted(
        stepId: Long,
        displayCommand: String,
        storageBefore: StorageSnapshot
    ) {
        stepDao.recordTelemetryStarted(
            id = stepId,
            displayCommand = displayCommand,
            storageTotalBytes = storageBefore.totalBytes,
            storageAvailableBytes = storageBefore.availableBytes,
            storageReserveBytes = storageBefore.reserveBytes,
            storageCapturedAtMs = storageBefore.capturedAtMs
        )
    }

    override suspend fun recordStepFinished(
        stepId: Long,
        durationMs: Long,
        storageAfter: StorageSnapshot,
        verificationSource: String
    ) {
        stepDao.recordTelemetryFinished(
            id = stepId,
            durationMs = durationMs,
            storageTotalBytes = storageAfter.totalBytes,
            storageAvailableBytes = storageAfter.availableBytes,
            storageCapturedAtMs = storageAfter.capturedAtMs,
            verificationSource = verificationSource
        )
    }

    override suspend fun finishRun(
        runId: Long,
        status: OptimizationRunStatus,
        statusMessage: String?,
        progress: OptimizationProgress,
        storageAfter: StorageSnapshot
    ) {
        val canceledCount = if (status == OptimizationRunStatus.CANCELED) {
            (progress.totalCount - progress.processedCount).coerceAtLeast(0)
        } else {
            0
        }
        runDao.finishRun(
            runId = runId,
            status = status.name,
            statusMessage = statusMessage,
            finishedAtMs = if (status.isTerminal) System.currentTimeMillis() else null,
            processedCount = progress.processedCount,
            optimizedSucceededCount = progress.optimizedSucceededCount,
            alreadyOptimizedCount = progress.alreadyOptimizedCount,
            skippedNoProfileCount = progress.skippedNoProfileCount,
            failedOrRefusedCount = progress.failedOrRefusedCount,
            unverifiedCount = progress.unverifiedCount,
            canceledCount = canceledCount,
            storageTotalAfterBytes = storageAfter.totalBytes,
            storageAvailableAfterBytes = storageAfter.availableBytes,
            storageCapturedAfterAtMs = storageAfter.capturedAtMs
        )
    }

    override suspend fun recordExportResult(runId: Long, result: TelemetryExportResult) {
        when (result) {
            is TelemetryExportResult.Success -> runDao.recordExportSuccess(
                runId = runId,
                uri = result.uri,
                exportedAtMs = result.exportedAtMs
            )
            is TelemetryExportResult.Failure -> runDao.recordExportFailure(runId, result.message)
        }
    }

    override suspend fun pruneToLatest(retainCount: Int) {
        require(retainCount > 0) { "retainCount must be positive" }
        runDao.pruneToLatest(retainCount)
    }
}

private fun OptimizationRunEntity.toDomain(): OptimizationRunTelemetry = OptimizationRunTelemetry(
    runId = runId,
    modeKey = modeKey,
    requestedCompilerFilter = requestedCompilerFilter,
    fullDexoptScope = fullDexoptScope,
    forceOptimize = forceOptimize,
    status = runCatching { OptimizationRunStatus.valueOf(status) }
        .getOrDefault(OptimizationRunStatus.FAILED),
    statusMessage = statusMessage,
    startedAtMs = startedAtMs,
    finishedAtMs = finishedAtMs,
    totalTargetedCount = totalTargetedCount,
    processedCount = processedCount,
    optimizedSucceededCount = optimizedSucceededCount,
    alreadyOptimizedCount = alreadyOptimizedCount,
    skippedNoProfileCount = skippedNoProfileCount,
    failedOrRefusedCount = failedOrRefusedCount,
    unverifiedCount = unverifiedCount,
    canceledCount = canceledCount,
    storageBefore = StorageSnapshot(
        totalBytes = storageTotalBeforeBytes,
        availableBytes = storageAvailableBeforeBytes,
        reserveBytes = storageReserveBytes,
        capturedAtMs = storageCapturedBeforeAtMs
    ),
    storageAfter = storageSnapshotOrNull(
        totalBytes = storageTotalAfterBytes,
        availableBytes = storageAvailableAfterBytes,
        reserveBytes = storageReserveBytes,
        capturedAtMs = storageCapturedAfterAtMs
    ),
    appVersionName = appVersionName,
    appVersionCode = appVersionCode,
    deviceManufacturer = deviceManufacturer,
    deviceModel = deviceModel,
    sdkInt = sdkInt,
    buildFingerprint = buildFingerprint,
    threadPolicy = threadPolicy,
    exportUri = exportUri,
    exportError = exportError,
    exportedAtMs = exportedAtMs
)

private fun OptimizationStepEntity.toDomain(): OptimizationStepTelemetry = OptimizationStepTelemetry(
    id = id,
    runId = runId,
    stepIndex = stepIndex,
    totalSteps = totalSteps,
    packageName = packageName,
    modeKey = mode,
    forceOptimize = forceOptimize,
    status = status,
    beforeFilter = beforeFilter,
    afterFilter = afterFilter,
    exitCode = exitCode,
    stdout = stdout,
    stderr = stderr,
    displayCommand = displayCommand,
    durationMs = durationMs,
    storageBefore = storageSnapshotOrNull(
        totalBytes = storageTotalBeforeBytes,
        availableBytes = storageAvailableBeforeBytes,
        reserveBytes = storageReserveBytes,
        capturedAtMs = storageCapturedBeforeAtMs
    ),
    storageAfter = storageSnapshotOrNull(
        totalBytes = storageTotalAfterBytes,
        availableBytes = storageAvailableAfterBytes,
        reserveBytes = storageReserveBytes,
        capturedAtMs = storageCapturedAfterAtMs
    ),
    verificationSource = verificationSource,
    createdAtMs = createdAtMs,
    updatedAtMs = updatedAtMs
)

private fun storageSnapshotOrNull(
    totalBytes: Long?,
    availableBytes: Long?,
    reserveBytes: Long?,
    capturedAtMs: Long?
): StorageSnapshot? {
    if (totalBytes == null || availableBytes == null || reserveBytes == null || capturedAtMs == null) {
        return null
    }
    return StorageSnapshot(totalBytes, availableBytes, reserveBytes, capturedAtMs)
}

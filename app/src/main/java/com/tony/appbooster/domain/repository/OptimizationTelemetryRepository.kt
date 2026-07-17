package com.tony.appbooster.domain.repository

import com.tony.appbooster.domain.model.common.OptimizationProgress
import com.tony.appbooster.domain.model.settings.AppOptimizationType
import com.tony.appbooster.domain.model.telemetry.OptimizationRunStatus
import com.tony.appbooster.domain.model.telemetry.OptimizationRunTelemetry
import com.tony.appbooster.domain.model.telemetry.OptimizationStepTelemetry
import com.tony.appbooster.domain.model.telemetry.StorageSnapshot
import com.tony.appbooster.domain.model.telemetry.TelemetryExportResult
import kotlinx.coroutines.flow.Flow

interface OptimizationTelemetryRepository {
    fun observeLatestRun(): Flow<OptimizationRunTelemetry?>

    suspend fun getRun(runId: Long): OptimizationRunTelemetry?

    suspend fun getLatestRun(): OptimizationRunTelemetry?

    suspend fun getSteps(runId: Long): List<OptimizationStepTelemetry>

    suspend fun startOrResumeRun(
        runId: Long,
        mode: AppOptimizationType,
        forceOptimize: Boolean,
        storageBefore: StorageSnapshot
    )

    suspend fun updatePlanCounts(
        runId: Long,
        totalTargetedCount: Int,
        alreadyOptimizedCount: Int,
        skippedNoProfileCount: Int
    )

    suspend fun updateProgress(runId: Long, progress: OptimizationProgress)

    suspend fun recordStepStarted(
        stepId: Long,
        displayCommand: String,
        storageBefore: StorageSnapshot
    )

    suspend fun recordStepFinished(
        stepId: Long,
        durationMs: Long,
        storageAfter: StorageSnapshot,
        verificationSource: String
    )

    suspend fun finishRun(
        runId: Long,
        status: OptimizationRunStatus,
        statusMessage: String?,
        progress: OptimizationProgress,
        storageAfter: StorageSnapshot
    )

    suspend fun recordExportResult(runId: Long, result: TelemetryExportResult)

    suspend fun pruneToLatest(retainCount: Int)
}

package com.tony.appbooster.data.local.optimization

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface OptimizationStepDao {

    @Query(
        """
        SELECT steps.runId FROM optimization_steps AS steps
        LEFT JOIN optimization_runs AS runs ON runs.runId = steps.runId
        WHERE steps.mode = :mode
          AND steps.forceOptimize = :forceOptimize
          AND (runs.runId IS NULL OR runs.status IN ('RUNNING', 'PAUSED'))
        GROUP BY steps.runId
        HAVING SUM(CASE WHEN steps.status IN ('PENDING', 'RUNNING') THEN 1 ELSE 0 END) > 0
           AND SUM(CASE WHEN steps.status = 'CANCELED' THEN 1 ELSE 0 END) = 0
        ORDER BY MAX(steps.createdAtMs) DESC
        LIMIT 1
        """
    )
    suspend fun findLatestResumableRunId(mode: String, forceOptimize: Boolean): Long?

    @Query("SELECT * FROM optimization_steps WHERE runId = :runId ORDER BY stepIndex ASC")
    suspend fun getStepsForRun(runId: Long): List<OptimizationStepEntity>

    @Query(
        """
        SELECT * FROM optimization_steps
        WHERE packageName = :packageName
          AND requestedFilter = :requestedFilter
          AND android_build = :androidBuild
          AND art_module_version = :artModuleVersion
          AND stableOsAdjusted = 1
          AND outcome = 'OS_ADJUSTED_FILTER'
          AND (
              packageLastUpdateTimeMs = :packageLastUpdateTimeMs OR
              (packageLastUpdateTimeMs IS NULL AND :packageLastUpdateTimeMs IS NULL)
          )
        ORDER BY updatedAtMs DESC
        LIMIT 1
        """
    )
    suspend fun findStableAdjustedOutcome(
        packageName: String,
        requestedFilter: String,
        androidBuild: String,
        artModuleVersion: String,
        packageLastUpdateTimeMs: Long?
    ): OptimizationStepEntity?

    @Query(
        """
        SELECT * FROM optimization_steps
        WHERE id IN (
            SELECT MAX(id) FROM optimization_steps
            WHERE status = 'SUCCEEDED'
            GROUP BY packageName
        )
        ORDER BY updatedAtMs DESC
        LIMIT 50
        """
    )
    fun observeRollbackCandidates(): Flow<List<OptimizationStepEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(steps: List<OptimizationStepEntity>)

    @Query(
        """
        UPDATE optimization_steps
        SET status = 'PENDING', updatedAtMs = :updatedAtMs
        WHERE runId = :runId AND status = 'RUNNING'
        """
    )
    suspend fun resetRunningSteps(runId: Long, updatedAtMs: Long)

    @Query(
        """
        UPDATE optimization_steps
        SET status = 'RUNNING',
            beforeFilter = :beforeFilter,
            updatedAtMs = :updatedAtMs
        WHERE id = :id
        """
    )
    suspend fun markRunning(id: Long, beforeFilter: String?, updatedAtMs: Long)

    @Query(
        """
        UPDATE optimization_steps
        SET status = 'SUCCEEDED',
            afterFilter = :afterFilter,
            exitCode = :exitCode,
            stdout = :stdout,
            stderr = :stderr,
            updatedAtMs = :updatedAtMs
        WHERE id = :id
        """
    )
    suspend fun markSucceeded(
        id: Long,
        afterFilter: String?,
        exitCode: Int,
        stdout: String,
        stderr: String,
        updatedAtMs: Long
    )

    @Query(
        """
        UPDATE optimization_steps
        SET status = 'FAILED',
            exitCode = :exitCode,
            stdout = :stdout,
            stderr = :stderr,
            updatedAtMs = :updatedAtMs
        WHERE id = :id
        """
    )
    suspend fun markFailed(
        id: Long,
        exitCode: Int?,
        stdout: String?,
        stderr: String?,
        updatedAtMs: Long
    )

    @Query(
        """
        UPDATE optimization_steps
        SET status = 'UNVERIFIED',
            afterFilter = :afterFilter,
            exitCode = :exitCode,
            stdout = :stdout,
            stderr = :stderr,
            updatedAtMs = :updatedAtMs
        WHERE id = :id
        """
    )
    suspend fun markUnverified(
        id: Long,
        afterFilter: String?,
        exitCode: Int,
        stdout: String,
        stderr: String,
        updatedAtMs: Long
    )

    @Query(
        """
        UPDATE optimization_steps
        SET status = :status,
            afterFilter = :afterFilter,
            exitCode = :exitCode,
            stdout = :stdout,
            stderr = :stderr,
            updatedAtMs = :updatedAtMs
        WHERE id = :id
        """
    )
    suspend fun markClassifiedResult(
        id: Long,
        status: String,
        afterFilter: String?,
        exitCode: Int,
        stdout: String,
        stderr: String,
        updatedAtMs: Long
    )

    @Query(
        """
        UPDATE optimization_steps
        SET outcome = :outcome,
            requestedFilter = :requestedFilter,
            artStatus = :artStatus,
            artFinalStatus = :artFinalStatus,
            artSizeBytes = :artSizeBytes,
            artSizeBeforeBytes = :artSizeBeforeBytes,
            android_build = :androidBuild,
            art_module_version = :artModuleVersion,
            packageLastUpdateTimeMs = :packageLastUpdateTimeMs,
            stableOsAdjusted = :stableOsAdjusted,
            updatedAtMs = :updatedAtMs
        WHERE id = :id
        """
    )
    suspend fun recordOutcome(
        id: Long,
        outcome: String,
        requestedFilter: String,
        artStatus: String?,
        artFinalStatus: String?,
        artSizeBytes: Long?,
        artSizeBeforeBytes: Long?,
        androidBuild: String,
        artModuleVersion: String,
        packageLastUpdateTimeMs: Long?,
        stableOsAdjusted: Boolean,
        updatedAtMs: Long
    )

    @Query(
        """
        UPDATE optimization_steps
        SET displayCommand = :displayCommand,
            storageTotalBeforeBytes = :storageTotalBytes,
            storageAvailableBeforeBytes = :storageAvailableBytes,
            storageReserveBytes = :storageReserveBytes,
            storageCapturedBeforeAtMs = :storageCapturedAtMs
        WHERE id = :id
        """
    )
    suspend fun recordTelemetryStarted(
        id: Long,
        displayCommand: String,
        storageTotalBytes: Long,
        storageAvailableBytes: Long,
        storageReserveBytes: Long,
        storageCapturedAtMs: Long
    )

    @Query(
        """
        UPDATE optimization_steps
        SET durationMs = :durationMs,
            storageTotalAfterBytes = :storageTotalBytes,
            storageAvailableAfterBytes = :storageAvailableBytes,
            storageCapturedAfterAtMs = :storageCapturedAtMs,
            verificationSource = :verificationSource
        WHERE id = :id
        """
    )
    suspend fun recordTelemetryFinished(
        id: Long,
        durationMs: Long,
        storageTotalBytes: Long,
        storageAvailableBytes: Long,
        storageCapturedAtMs: Long,
        verificationSource: String
    )

    @Query(
        """
        UPDATE optimization_steps
        SET status = 'CANCELED', updatedAtMs = :updatedAtMs
        WHERE runId = :runId AND status IN ('PENDING', 'RUNNING')
        """
    )
    suspend fun markRunCanceled(runId: Long, updatedAtMs: Long)

    @Transaction
    suspend fun prepareResumedRun(runId: Long, updatedAtMs: Long): List<OptimizationStepEntity> {
        resetRunningSteps(runId, updatedAtMs)
        return getStepsForRun(runId)
    }
}

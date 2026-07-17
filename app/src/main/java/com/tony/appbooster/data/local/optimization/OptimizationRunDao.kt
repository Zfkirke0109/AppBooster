package com.tony.appbooster.data.local.optimization

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface OptimizationRunDao {
    @Query("SELECT * FROM optimization_runs ORDER BY startedAtMs DESC LIMIT 1")
    fun observeLatestRun(): Flow<OptimizationRunEntity?>

    @Query("SELECT * FROM optimization_runs WHERE runId = :runId LIMIT 1")
    suspend fun getRun(runId: Long): OptimizationRunEntity?

    @Query("SELECT * FROM optimization_runs ORDER BY startedAtMs DESC LIMIT 1")
    suspend fun getLatestRun(): OptimizationRunEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(run: OptimizationRunEntity)

    @Query(
        """
        UPDATE optimization_runs
        SET totalTargetedCount = :totalTargetedCount,
            alreadyOptimizedCount = :alreadyOptimizedCount,
            skippedNoProfileCount = :skippedNoProfileCount
        WHERE runId = :runId
        """
    )
    suspend fun updatePlanCounts(
        runId: Long,
        totalTargetedCount: Int,
        alreadyOptimizedCount: Int,
        skippedNoProfileCount: Int
    )

    @Query(
        """
        UPDATE optimization_runs
        SET processedCount = :processedCount,
            optimizedSucceededCount = :optimizedSucceededCount,
            alreadyOptimizedCount = :alreadyOptimizedCount,
            skippedNoProfileCount = :skippedNoProfileCount,
            failedOrRefusedCount = :failedOrRefusedCount,
            unverifiedCount = :unverifiedCount
        WHERE runId = :runId
        """
    )
    suspend fun updateProgress(
        runId: Long,
        processedCount: Int,
        optimizedSucceededCount: Int,
        alreadyOptimizedCount: Int,
        skippedNoProfileCount: Int,
        failedOrRefusedCount: Int,
        unverifiedCount: Int
    )

    @Query(
        """
        UPDATE optimization_runs
        SET status = :status,
            statusMessage = :statusMessage,
            finishedAtMs = :finishedAtMs,
            processedCount = :processedCount,
            optimizedSucceededCount = :optimizedSucceededCount,
            alreadyOptimizedCount = :alreadyOptimizedCount,
            skippedNoProfileCount = :skippedNoProfileCount,
            failedOrRefusedCount = :failedOrRefusedCount,
            unverifiedCount = :unverifiedCount,
            canceledCount = :canceledCount,
            storageTotalAfterBytes = :storageTotalAfterBytes,
            storageAvailableAfterBytes = :storageAvailableAfterBytes,
            storageCapturedAfterAtMs = :storageCapturedAfterAtMs
        WHERE runId = :runId
        """
    )
    suspend fun finishRun(
        runId: Long,
        status: String,
        statusMessage: String?,
        finishedAtMs: Long?,
        processedCount: Int,
        optimizedSucceededCount: Int,
        alreadyOptimizedCount: Int,
        skippedNoProfileCount: Int,
        failedOrRefusedCount: Int,
        unverifiedCount: Int,
        canceledCount: Int,
        storageTotalAfterBytes: Long,
        storageAvailableAfterBytes: Long,
        storageCapturedAfterAtMs: Long
    )

    @Query(
        """
        UPDATE optimization_runs
        SET exportUri = :uri, exportError = NULL, exportedAtMs = :exportedAtMs
        WHERE runId = :runId
        """
    )
    suspend fun recordExportSuccess(runId: Long, uri: String, exportedAtMs: Long)

    @Query(
        """
        UPDATE optimization_runs
        SET exportError = :message
        WHERE runId = :runId
        """
    )
    suspend fun recordExportFailure(runId: Long, message: String)

    @Query(
        """
        SELECT runId FROM optimization_runs
        ORDER BY startedAtMs DESC
        LIMIT -1 OFFSET :retainCount
        """
    )
    suspend fun getRunIdsBeyondRetention(retainCount: Int): List<Long>

    @Query("DELETE FROM optimization_steps WHERE runId IN (:runIds)")
    suspend fun deleteStepsForRuns(runIds: List<Long>)

    @Query("DELETE FROM optimization_runs WHERE runId IN (:runIds)")
    suspend fun deleteRuns(runIds: List<Long>)

    @Transaction
    suspend fun pruneToLatest(retainCount: Int) {
        val runIds = getRunIdsBeyondRetention(retainCount)
        if (runIds.isEmpty()) return
        deleteStepsForRuns(runIds)
        deleteRuns(runIds)
    }
}

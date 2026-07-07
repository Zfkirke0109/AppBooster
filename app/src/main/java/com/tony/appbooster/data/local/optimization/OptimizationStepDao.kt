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
        SELECT runId FROM optimization_steps
        WHERE mode = :mode AND forceOptimize = :forceOptimize
        GROUP BY runId
        HAVING SUM(CASE WHEN status IN ('PENDING', 'RUNNING') THEN 1 ELSE 0 END) > 0
           AND SUM(CASE WHEN status IN ('FAILED', 'CANCELED') THEN 1 ELSE 0 END) = 0
        ORDER BY MAX(createdAtMs) DESC
        LIMIT 1
        """
    )
    suspend fun findLatestResumableRunId(mode: String, forceOptimize: Boolean): Long?

    @Query("SELECT * FROM optimization_steps WHERE runId = :runId ORDER BY stepIndex ASC")
    suspend fun getStepsForRun(runId: Long): List<OptimizationStepEntity>

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

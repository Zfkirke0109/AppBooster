package com.tony.appbooster.data.local.optimization

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "optimization_steps",
    indices = [
        Index(value = ["runId"]),
        Index(value = ["runId", "stepIndex"], unique = true),
        Index(value = ["runId", "packageName"], unique = true),
        Index(value = ["packageName", "outcome", "requestedFilter"])
    ]
)
data class OptimizationStepEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val runId: Long,
    val stepIndex: Int,
    val totalSteps: Int,
    val skippedCount: Int,
    val packageName: String,
    val mode: String,
    val forceOptimize: Boolean,
    val status: String = OptimizationStepStatus.PENDING,
    val outcome: String? = null,
    val requestedFilter: String? = null,
    val beforeFilter: String? = null,
    val afterFilter: String? = null,
    val artStatus: String? = null,
    val artFinalStatus: String? = null,
    val artSizeBytes: Long? = null,
    val artSizeBeforeBytes: Long? = null,
    @ColumnInfo(name = "android_build")
    val androidBuild: String? = null,
    @ColumnInfo(name = "art_module_version")
    val artModuleVersion: String? = null,
    val packageLastUpdateTimeMs: Long? = null,
    @ColumnInfo(defaultValue = "0")
    val stableOsAdjusted: Boolean = false,
    val exitCode: Int? = null,
    val stdout: String? = null,
    val stderr: String? = null,
    val displayCommand: String? = null,
    val durationMs: Long? = null,
    val storageTotalBeforeBytes: Long? = null,
    val storageAvailableBeforeBytes: Long? = null,
    val storageReserveBytes: Long? = null,
    val storageCapturedBeforeAtMs: Long? = null,
    val storageTotalAfterBytes: Long? = null,
    val storageAvailableAfterBytes: Long? = null,
    val storageCapturedAfterAtMs: Long? = null,
    val verificationSource: String? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = createdAtMs
)

object OptimizationStepStatus {
    const val PENDING = "PENDING"
    const val RUNNING = "RUNNING"
    const val SUCCEEDED = "SUCCEEDED"
    const val FAILED = "FAILED"
    const val UNVERIFIED = "UNVERIFIED"
    const val OS_ADJUSTED = "OS_ADJUSTED_FILTER"
    const val SKIPPED_NOT_APPLICABLE = "SKIPPED_NOT_APPLICABLE"
    const val VERIFICATION_UNAVAILABLE = "VERIFICATION_UNAVAILABLE"
    const val CANCELED = "CANCELED"
}

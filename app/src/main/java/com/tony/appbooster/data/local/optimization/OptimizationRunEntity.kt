package com.tony.appbooster.data.local.optimization

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "optimization_runs",
    indices = [
        Index(value = ["startedAtMs"]),
        Index(value = ["status"])
    ]
)
data class OptimizationRunEntity(
    @PrimaryKey
    val runId: Long,
    val modeKey: String,
    val requestedCompilerFilter: String,
    val fullDexoptScope: Boolean,
    val forceOptimize: Boolean,
    val status: String,
    val statusMessage: String? = null,
    val startedAtMs: Long,
    val finishedAtMs: Long? = null,
    val totalTargetedCount: Int = 0,
    val processedCount: Int = 0,
    val optimizedSucceededCount: Int = 0,
    val alreadyOptimizedCount: Int = 0,
    val skippedNoProfileCount: Int = 0,
    val failedOrRefusedCount: Int = 0,
    val unverifiedCount: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val osAdjustedFilterCount: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val skippedNotApplicableCount: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val verificationUnavailableCount: Int = 0,
    val canceledCount: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val artStorageDeltaBytes: Long = 0L,
    val storageTotalBeforeBytes: Long,
    val storageAvailableBeforeBytes: Long,
    val storageReserveBytes: Long,
    val storageCapturedBeforeAtMs: Long,
    val storageTotalAfterBytes: Long? = null,
    val storageAvailableAfterBytes: Long? = null,
    val storageCapturedAfterAtMs: Long? = null,
    val appVersionName: String,
    val appVersionCode: Long,
    val deviceManufacturer: String,
    val deviceModel: String,
    val sdkInt: Int,
    val buildFingerprint: String,
    @ColumnInfo(name = "android_build", defaultValue = "''")
    val androidBuild: String = buildFingerprint,
    @ColumnInfo(name = "art_module_version", defaultValue = "'unknown'")
    val artModuleVersion: String = "unknown",
    val threadPolicy: String,
    val exportUri: String? = null,
    val exportError: String? = null,
    val exportedAtMs: Long? = null
)

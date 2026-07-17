package com.tony.appbooster.domain.model.telemetry

data class OptimizationRunTelemetry(
    val runId: Long,
    val modeKey: String,
    val requestedCompilerFilter: String,
    val fullDexoptScope: Boolean,
    val forceOptimize: Boolean,
    val status: OptimizationRunStatus,
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
    val canceledCount: Int = 0,
    val storageBefore: StorageSnapshot,
    val storageAfter: StorageSnapshot? = null,
    val appVersionName: String,
    val appVersionCode: Long,
    val deviceManufacturer: String,
    val deviceModel: String,
    val sdkInt: Int,
    val buildFingerprint: String,
    val threadPolicy: String = PACKAGE_MANAGER_THREAD_POLICY,
    val exportUri: String? = null,
    val exportError: String? = null,
    val exportedAtMs: Long? = null
) {
    companion object {
        const val PACKAGE_MANAGER_THREAD_POLICY = "package-manager-managed"
    }
}

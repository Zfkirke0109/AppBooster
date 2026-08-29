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
    val osAdjustedFilterCount: Int = 0,
    val skippedNotApplicableCount: Int = 0,
    val verificationUnavailableCount: Int = 0,
    val canceledCount: Int = 0,
    val artStorageDeltaBytes: Long = 0L,
    val storageBefore: StorageSnapshot,
    val storageAfter: StorageSnapshot? = null,
    val appVersionName: String,
    val appVersionCode: Long,
    val deviceManufacturer: String,
    val deviceModel: String,
    val sdkInt: Int,
    val buildFingerprint: String,
    val androidBuild: String = buildFingerprint,
    val artModuleVersion: String = UNKNOWN_ART_MODULE_VERSION,
    val threadPolicy: String = PACKAGE_MANAGER_THREAD_POLICY,
    val exportUri: String? = null,
    val exportError: String? = null,
    val exportedAtMs: Long? = null
) {
    /** Packages verified at the requested compiler filter during this run. */
    val successCount: Int
        get() = optimizedSucceededCount

    /** Packages intentionally skipped because no compile command was applicable. */
    val skippedCount: Int
        get() = alreadyOptimizedCount + skippedNoProfileCount + skippedNotApplicableCount

    /** Packages whose compile command failed or was refused. */
    val failedCount: Int
        get() = failedOrRefusedCount

    /** Packages that ran without failure but could not be verified at the requested filter. */
    val explicitlyUnverifiedCount: Int
        get() = osAdjustedFilterCount + verificationUnavailableCount

    companion object {
        const val PACKAGE_MANAGER_THREAD_POLICY = "package-manager-managed"
        const val UNKNOWN_ART_MODULE_VERSION = "unknown"
    }
}

/** Durable, user-facing classification of a package compile attempt. */
enum class OptimizationStepOutcome {
    VERIFIED_REQUESTED_FILTER,
    OS_ADJUSTED_FILTER,
    SKIPPED_NOT_APPLICABLE,
    VERIFICATION_UNAVAILABLE,
    FAILED_OR_REFUSED;

    companion object {
        fun fromStoredValue(value: String?): OptimizationStepOutcome? =
            value?.let { stored -> entries.firstOrNull { it.name == stored } }
    }
}

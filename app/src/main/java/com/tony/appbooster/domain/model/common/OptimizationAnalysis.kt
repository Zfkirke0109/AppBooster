package com.tony.appbooster.domain.model.common

import com.tony.appbooster.domain.model.settings.AppOptimizationType

/**
 * Represents the result of a pre-optimization analysis scan.
 *
 * This data is collected before optimization starts to show the user
 * how many apps need optimization vs are already optimized.
 *
 * @property totalAppsScanned Total number of installed apps that were analyzed so far.
 * @property totalAppsToScan Total number of apps to be scanned (for progress calculation).
 * @property appsNeedingOptimization Number of apps that need optimization.
 * @property appsAlreadyOptimized Number of apps that are already optimized (will be skipped).
 * @property appsWithNoProfile Number of apps with no runtime profile (never used, "verify" filter).
 *           These cannot benefit from profile-guided optimization.
 * @property packagesNeedingOptimization List of package names that need optimization (cached for reuse).
 * @property isScanning Whether the analysis is currently in progress.
 * @property currentPackage Package currently being analyzed, empty if not scanning.
 * @property lastScanTimeMs Timestamp of when the last scan completed, or null if never scanned.
 * @property mode The optimization mode this analysis was computed for, or null if not yet scanned.
 *           Used to invalidate the cache when the user switches modes before re-running.
 */
data class OptimizationAnalysis(
    val totalAppsScanned: Int = 0,
    val totalAppsToScan: Int = 0,
    val appsNeedingOptimization: Int = 0,
    val appsAlreadyOptimized: Int = 0,
    val appsWithNoProfile: Int = 0,
    val failedOrRefusedCount: Int = 0,
    val unverifiedCount: Int = 0,
    val osAdjustedFilterCount: Int = 0,
    val skippedNotApplicableCount: Int = 0,
    val verificationUnavailableCount: Int = 0,
    val packagesNeedingOptimization: List<String> = emptyList(),
    val isScanning: Boolean = false,
    val currentPackage: String = "",
    val lastScanTimeMs: Long? = null,
    val mode: AppOptimizationType? = null
) {
    /**
     * Whether a scan has been completed at least once.
     */
    val hasScanned: Boolean
        get() = lastScanTimeMs != null

    /**
     * Whether all apps are already optimized (nothing to do).
     */
    val allOptimized: Boolean
        get() = hasScanned &&
            totalAppsScanned > 0 &&
            appsNeedingOptimization == 0 &&
            failedOrRefusedCount == 0 &&
            unverifiedCount == 0 &&
            verificationUnavailableCount == 0 &&
            appsAlreadyOptimized > 0

    /**
     * Progress of the current scan from 0f to 1f.
     */
    val progress: Float
        get() = if (totalAppsToScan > 0) totalAppsScanned.toFloat() / totalAppsToScan else 0f
}

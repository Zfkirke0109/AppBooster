package com.tony.appbooster.domain.model.common

/**
 * Represents the current state of app optimization progress.
 *
 * Business purpose:
 * - Streams live progress for foreground UI and WorkManager notifications.
 * - Exposes a stable [runId] so presentation can associate one-time UI affordances
 *   (e.g., result banners) with a specific optimization run.
 * - Makes the final result explicit via [result] to avoid fragile string-based checks.
 *
 * @property runId Identifier of the current/most recent optimization run.
 * A value of `0L` indicates that no run has been started yet.
 * @property isRunning Whether optimization is currently in progress.
 * @property result Final result of the most recent run.
 * @property currentAppPackage The package name of the app currently being optimized.
 * @property progress Progress value from 0.0 to 1.0.
 * @property processedCount Number of target packages processed for progress.
 * @property skippedCount Number of apps skipped before compile (already optimized or no profile).
 * @property optimizedSucceededCount Number of target packages verified after compile.
 * @property alreadyOptimizedCount Number of target packages skipped because they already matched.
 * @property skippedNoProfileCount Number of speed-profile targets skipped because no runtime profile exists.
 * @property failedOrRefusedCount Number of target packages whose compile command failed or was refused.
 * @property unverifiedCount Number of target packages whose command returned success but post-run evidence was unclear.
 * @property osAdjustedFilterCount Number of packages where ART applied a different filter.
 * @property skippedNotApplicableCount Number of packages ART reported as not applicable.
 * @property verificationUnavailableCount Number of packages lacking post-run ART evidence.
 * @property totalCount Total number of apps to optimize.
 */
data class OptimizationProgress(
    val runId: Long = 0L,
    val isRunning: Boolean = false,
    val result: OptimizationResult = OptimizationResult.None,
    val currentAppPackage: String = "",
    val progress: Float = 0f,
    val processedCount: Int = 0,
    val skippedCount: Int = 0,
    val optimizedSucceededCount: Int = 0,
    val alreadyOptimizedCount: Int = 0,
    val skippedNoProfileCount: Int = 0,
    val failedOrRefusedCount: Int = 0,
    val unverifiedCount: Int = 0,
    val osAdjustedFilterCount: Int = 0,
    val skippedNotApplicableCount: Int = 0,
    val verificationUnavailableCount: Int = 0,
    val totalCount: Int = 0
)

/**
 * Describes the final outcome of an optimization run.
 */
sealed interface OptimizationResult {

    /**
     * No run has finished yet, or state was reset for a new run.
     */
    data object None : OptimizationResult

    /**
     * The optimization flow finished normally.
     */
    data object Completed : OptimizationResult

    /**
     * The optimization flow finished, but one or more packages failed/refused
     * the command or could not be verified with ART/package-manager evidence.
     */
    data object CompletedWithIssues : OptimizationResult

    /**
     * The optimization flow was stopped before completion.
     */
    data object Canceled : OptimizationResult

    /**
     * The optimization flow stopped because a shell command failed.
     */
    data object Failed : OptimizationResult

    /**
     * The optimization flow paused because device health guards blocked it.
     */
    data class Paused(val reason: String) : OptimizationResult
}

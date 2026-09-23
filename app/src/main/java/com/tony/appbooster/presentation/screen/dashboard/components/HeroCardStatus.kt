package com.tony.appbooster.presentation.screen.dashboard.components

import com.tony.appbooster.domain.model.common.OptimizationProgress
import com.tony.appbooster.domain.model.common.OptimizationResult
import com.tony.appbooster.domain.model.settings.AppOptimizationType

/**
 * Presentation outcome for the dashboard, retaining the same counters for every terminal state.
 *
 * @property counts Explicit outcome populations from the run, never inferred from aggregate skips.
 * @property optimizationMode Mode whose requested filter is represented by the matching count.
 */
sealed interface HeroCardStatus {
    val counts: HeroResultCounts
    val optimizationMode: AppOptimizationType

    /** Whether Android adjusted the filter without command failures or missing verification. */
    val isAndroidAdjustedCompletion: Boolean
        get() = this is CompletedWithIssues && counts.osAdjustedCount > 0 &&
            counts.failedCount == 0 && counts.verificationUnavailableCount == 0

    /** A run that finished without interruption. */
    data class Completed(
        override val counts: HeroResultCounts,
        override val optimizationMode: AppOptimizationType = AppOptimizationType.SPEED_PROFILE
    ) : HeroCardStatus

    /** A finished run containing adjustments, command failures, or missing verification. */
    data class CompletedWithIssues(
        override val counts: HeroResultCounts,
        override val optimizationMode: AppOptimizationType = AppOptimizationType.SPEED_PROFILE
    ) : HeroCardStatus

    /** A run stopped by the user, retaining all outcomes reached before cancellation. */
    data class Canceled(
        override val counts: HeroResultCounts,
        override val optimizationMode: AppOptimizationType = AppOptimizationType.SPEED_PROFILE
    ) : HeroCardStatus

    /** A run stopped by a workflow failure. */
    data class Failed(
        override val counts: HeroResultCounts,
        override val optimizationMode: AppOptimizationType = AppOptimizationType.SPEED_PROFILE
    ) : HeroCardStatus

    /** A run paused by a device health guard; [reason] explains the blocking condition. */
    data class Paused(
        val reason: String,
        override val counts: HeroResultCounts,
        override val optimizationMode: AppOptimizationType = AppOptimizationType.SPEED_PROFILE
    ) : HeroCardStatus

    /** Every considered package already matched; no other outcome population is present. */
    data class AllOptimized(
        override val counts: HeroResultCounts,
        override val optimizationMode: AppOptimizationType = AppOptimizationType.SPEED_PROFILE
    ) : HeroCardStatus

    companion object {
        /**
         * Maps run results without borrowing counters from a potentially stale analysis snapshot.
         *
         * @param progress Most recent run state; `processedCount` counts completed compile targets.
         * @param optimizationMode Mode selected for the run.
         * @return Terminal presentation state, or null while no result is available.
         */
        fun fromProgress(
            progress: OptimizationProgress,
            optimizationMode: AppOptimizationType
        ): HeroCardStatus? {
            val counts = HeroResultCounts(
                succeededCount = progress.optimizedSucceededCount,
                alreadyOptimizedCount = progress.alreadyOptimizedCount,
                noProfileCount = progress.skippedNoProfileCount,
                failedCount = progress.failedOrRefusedCount,
                osAdjustedCount = progress.osAdjustedFilterCount,
                skippedNotApplicableCount = progress.skippedNotApplicableCount,
                verificationUnavailableCount = progress.verificationUnavailableCount,
                // Initial skips are outside the compile queue. Never subtract them here.
                unprocessedCount = (progress.totalCount - progress.processedCount).coerceAtLeast(0)
            )
            return when (val result = progress.result) {
                OptimizationResult.None -> null
                OptimizationResult.Completed -> if (
                    counts.alreadyOptimizedCount > 0 && counts.succeededCount == 0 &&
                    counts.matchingCount == counts.totalCount
                ) {
                    AllOptimized(counts, optimizationMode)
                } else {
                    Completed(counts, optimizationMode)
                }
                OptimizationResult.CompletedWithIssues -> CompletedWithIssues(counts, optimizationMode)
                OptimizationResult.Canceled -> Canceled(counts, optimizationMode)
                OptimizationResult.Failed -> Failed(counts, optimizationMode)
                is OptimizationResult.Paused -> Paused(result.reason, counts, optimizationMode)
            }
        }
    }
}

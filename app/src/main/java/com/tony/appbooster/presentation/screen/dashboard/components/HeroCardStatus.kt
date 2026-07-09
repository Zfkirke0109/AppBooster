package com.tony.appbooster.presentation.screen.dashboard.components

import com.tony.appbooster.domain.model.settings.AppOptimizationType

/**
 * Represents every possible outcome state of the hero result card after an
 * optimization run completes or is interrupted.
 *
 * Each entry carries the data needed to drive a single, unified
 * [HeroResultPanel] composable instead of maintaining separate implementations
 * per outcome.
 *
 * @property processedCount Number of apps successfully optimized during the run.
 * @property skippedCount Number of apps skipped (already optimized or no profile).
 * @property totalCount Total apps targeted by the run (0 for [AllOptimized]).
 * @property optimizationMode The mode used for this run; controls which stats are shown
 *   (e.g. "no profile" is only meaningful for [AppOptimizationType.SPEED_PROFILE]).
 */
sealed interface HeroCardStatus {

    val processedCount: Int
    val skippedCount: Int
    val totalCount: Int
    val optimizationMode: AppOptimizationType

    /**
     * Optimization run finished without interruption.
     *
     * @property processedCount Apps fully optimized in this run.
     * @property skippedCount Apps skipped because they were already optimal.
     * @property totalCount Total apps that were targeted.
     * @property noProfileCount Apps that had no runtime profile (speed-profile mode only).
     * @property optimizationMode Mode used for this run.
     */
    data class Completed(
        override val processedCount: Int,
        override val skippedCount: Int,
        override val totalCount: Int,
        val noProfileCount: Int = 0,
        override val optimizationMode: AppOptimizationType = AppOptimizationType.SPEED_PROFILE
    ) : HeroCardStatus

    data class CompletedWithIssues(
        override val processedCount: Int,
        override val skippedCount: Int,
        val failedCount: Int,
        val unverifiedCount: Int,
        override val totalCount: Int,
        val noProfileCount: Int = 0,
        override val optimizationMode: AppOptimizationType = AppOptimizationType.SPEED_PROFILE
    ) : HeroCardStatus

    /**
     * Optimization run was stopped by the user before all apps were processed.
     *
     * @property processedCount Apps optimized before cancellation.
     * @property skippedCount Apps skipped before cancellation.
     * @property totalCount Total apps that were targeted.
     * @property noProfileCount Apps that had no runtime profile (speed-profile mode only).
     * @property optimizationMode Mode used for this run.
     */
    data class Canceled(
        override val processedCount: Int,
        override val skippedCount: Int,
        override val totalCount: Int,
        val noProfileCount: Int = 0,
        override val optimizationMode: AppOptimizationType = AppOptimizationType.SPEED_PROFILE
    ) : HeroCardStatus

    /**
     * Optimization run stopped because a shell command returned a non-zero exit code.
     *
     * @property processedCount Apps optimized before the failure.
     * @property skippedCount Apps skipped before the failure.
     * @property totalCount Total apps that were targeted.
     * @property noProfileCount Apps that had no runtime profile (speed-profile mode only).
     * @property optimizationMode Mode used for this run.
     */
    data class Failed(
        override val processedCount: Int,
        override val skippedCount: Int,
        override val totalCount: Int,
        val noProfileCount: Int = 0,
        override val optimizationMode: AppOptimizationType = AppOptimizationType.SPEED_PROFILE
    ) : HeroCardStatus

    /**
     * Optimization run was paused by battery or thermal guard checks.
     *
     * @property reason User-readable guard reason.
     */
    data class Paused(
        val reason: String,
        override val processedCount: Int,
        override val skippedCount: Int,
        override val totalCount: Int,
        val noProfileCount: Int = 0,
        override val optimizationMode: AppOptimizationType = AppOptimizationType.SPEED_PROFILE
    ) : HeroCardStatus

    /**
     * Every targeted app was already at peak optimization; nothing needed to run.
     *
     * @property optimizedCount Apps confirmed as already optimized.
     * @property noProfileCount Apps skipped because they have no runtime profile
     *   (only non-zero for [AppOptimizationType.SPEED_PROFILE]).
     * @property optimizationMode Mode used for this run.
     */
    data class AllOptimized(
        val optimizedCount: Int,
        val noProfileCount: Int = 0,
        override val optimizationMode: AppOptimizationType = AppOptimizationType.SPEED_PROFILE
    ) : HeroCardStatus {
        override val processedCount: Int = 0
        override val skippedCount: Int = noProfileCount
        override val totalCount: Int = optimizedCount + noProfileCount
    }
}


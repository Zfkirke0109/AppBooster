package com.tony.appbooster.presentation.worker

import com.tony.appbooster.domain.model.common.OptimizationResult

/**
 * Distinct key used to suppress redundant notification progress emissions.
 *
 * @property currentAppPackage Current package label shown by the notification.
 * @property progressPercent Normalized progress percent.
 * @property processedCount Number of processed apps.
 * @property totalCount Total apps in the run.
 * @property isRunning Whether the run is currently active.
 * @property result Terminal/non-terminal optimization result snapshot.
 */
internal data class OptimizationNotificationProgressKey(
    val currentAppPackage: String,
    val progressPercent: Int,
    val processedCount: Int,
    val totalCount: Int,
    val isRunning: Boolean,
    val result: OptimizationResult
)

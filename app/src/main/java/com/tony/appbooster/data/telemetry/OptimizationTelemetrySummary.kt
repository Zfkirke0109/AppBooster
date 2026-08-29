package com.tony.appbooster.data.telemetry

import com.tony.appbooster.domain.model.telemetry.OptimizationRunTelemetry
import com.tony.appbooster.domain.model.telemetry.OptimizationStepOutcome
import com.tony.appbooster.domain.model.telemetry.OptimizationStepTelemetry

/** Exact, non-overlapping terminal classifications used by telemetry schema 3. */
internal data class OptimizationTelemetrySummary(
    val targeted: Int,
    val attempted: Int,
    val success: Int,
    val skipped: Int,
    val failed: Int,
    val unverified: Int,
    val canceled: Int
) {
    val accounted: Int
        get() = success + skipped + failed + unverified + canceled

    val reconciled: Boolean
        get() = accounted == targeted

    companion object {
        fun from(
            run: OptimizationRunTelemetry,
            steps: List<OptimizationStepTelemetry>
        ) = OptimizationTelemetrySummary(
            targeted = run.totalTargetedCount,
            // A command was attempted only when the step reached execution.
            // Preflight not-applicable classifications remain processed/skipped.
            attempted = steps.count { step -> !step.displayCommand.isNullOrBlank() },
            success = run.successCount,
            skipped = run.skippedCount,
            failed = run.failedCount,
            unverified = run.explicitlyUnverifiedCount,
            canceled = run.canceledCount
        )
    }
}

internal fun OptimizationStepOutcome.exportResultClass(): String = when (this) {
    OptimizationStepOutcome.VERIFIED_REQUESTED_FILTER -> "SUCCESS"
    OptimizationStepOutcome.SKIPPED_NOT_APPLICABLE -> "SKIPPED"
    OptimizationStepOutcome.FAILED_OR_REFUSED -> "FAILED"
    OptimizationStepOutcome.OS_ADJUSTED_FILTER,
    OptimizationStepOutcome.VERIFICATION_UNAVAILABLE -> "UNVERIFIED"
}

package com.tony.appbooster.data.repository

import com.tony.appbooster.data.local.optimization.OptimizationStepEntity
import com.tony.appbooster.domain.model.telemetry.OptimizationRunTelemetry

/** Requires recorded identities before carrying package results into a resumed run. */
internal object ResumeIdentityPolicy {
    fun canReusePlan(
        steps: List<OptimizationStepEntity>,
        telemetry: OptimizationRunTelemetry?,
        currentAndroidBuild: String,
        currentArtModuleVersion: String
    ): Boolean {
        if (steps.isEmpty() || !isKnown(currentAndroidBuild) || !isKnown(currentArtModuleVersion)) {
            return false
        }
        if (steps.any { step ->
                // Analysis-time skips have counts but no per-package identity records.
                step.skippedCount != 0 ||
                    step.androidBuild != currentAndroidBuild ||
                    step.artModuleVersion != currentArtModuleVersion
            }
        ) return false

        return telemetry == null || (
            telemetry.androidBuild == currentAndroidBuild &&
                telemetry.artModuleVersion == currentArtModuleVersion &&
                telemetry.alreadyOptimizedCount == 0 &&
                telemetry.skippedNoProfileCount == 0 &&
                telemetry.totalTargetedCount <= steps.size
            )
    }

    fun hasSamePackageIdentity(recordedUpdateTimeMs: Long?, currentUpdateTimeMs: Long?): Boolean =
        recordedUpdateTimeMs != null && recordedUpdateTimeMs > 0L &&
            recordedUpdateTimeMs == currentUpdateTimeMs

    private fun isKnown(value: String): Boolean =
        value.isNotBlank() && !value.trim().equals("unknown", ignoreCase = true)
}

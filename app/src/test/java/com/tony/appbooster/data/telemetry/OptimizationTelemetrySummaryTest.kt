package com.tony.appbooster.data.telemetry

import com.tony.appbooster.domain.model.telemetry.OptimizationRunStatus
import com.tony.appbooster.domain.model.telemetry.OptimizationRunTelemetry
import com.tony.appbooster.domain.model.telemetry.OptimizationStepOutcome
import com.tony.appbooster.domain.model.telemetry.OptimizationStepTelemetry
import com.tony.appbooster.domain.model.telemetry.StorageSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OptimizationTelemetrySummaryTest {

    @Test
    fun `latest device totals reconcile not applicable as skipped`() {
        val run = runTelemetry(
            targeted = 778,
            success = 7,
            alreadyMatching = 735,
            osAdjusted = 8,
            notApplicable = 28,
            storedUnverified = 36
        )
        val steps = List(7) { index ->
            step(
                index = index,
                attempted = true,
                outcome = OptimizationStepOutcome.VERIFIED_REQUESTED_FILTER
            )
        } + List(3) { offset ->
            step(
                index = offset + 7,
                attempted = false,
                outcome = OptimizationStepOutcome.SKIPPED_NOT_APPLICABLE
            )
        }

        val summary = OptimizationTelemetrySummary.from(run, steps)

        assertEquals(778, summary.targeted)
        assertEquals(7, summary.attempted)
        assertEquals(7, summary.success)
        assertEquals(763, summary.skipped)
        assertEquals(0, summary.failed)
        assertEquals(8, summary.unverified)
        assertEquals(778, summary.accounted)
        assertTrue(summary.reconciled)
    }

    @Test
    fun `step outcomes map to non-overlapping export classes`() {
        assertEquals("SUCCESS", OptimizationStepOutcome.VERIFIED_REQUESTED_FILTER.exportResultClass())
        assertEquals("SKIPPED", OptimizationStepOutcome.SKIPPED_NOT_APPLICABLE.exportResultClass())
        assertEquals("FAILED", OptimizationStepOutcome.FAILED_OR_REFUSED.exportResultClass())
        assertEquals("UNVERIFIED", OptimizationStepOutcome.OS_ADJUSTED_FILTER.exportResultClass())
        assertEquals("UNVERIFIED", OptimizationStepOutcome.VERIFICATION_UNAVAILABLE.exportResultClass())
    }

    private fun runTelemetry(
        targeted: Int,
        success: Int,
        alreadyMatching: Int,
        osAdjusted: Int,
        notApplicable: Int,
        storedUnverified: Int
    ) = OptimizationRunTelemetry(
        runId = 1788023993636L,
        modeKey = "ADVANCED_FULL_COMPILE",
        requestedCompilerFilter = "speed",
        fullDexoptScope = true,
        forceOptimize = false,
        status = OptimizationRunStatus.COMPLETED_WITH_ISSUES,
        startedAtMs = 1L,
        finishedAtMs = 2L,
        totalTargetedCount = targeted,
        processedCount = 10,
        optimizedSucceededCount = success,
        alreadyOptimizedCount = alreadyMatching,
        unverifiedCount = storedUnverified,
        osAdjustedFilterCount = osAdjusted,
        skippedNotApplicableCount = notApplicable,
        storageBefore = StorageSnapshot(100L, 80L, 5L, 1L),
        appVersionName = "1.7.0",
        appVersionCode = 10700L,
        deviceManufacturer = "samsung",
        deviceModel = "SM-S918U1",
        sdkInt = 36,
        buildFingerprint = "device-test"
    )

    private fun step(
        index: Int,
        attempted: Boolean,
        outcome: OptimizationStepOutcome
    ) = OptimizationStepTelemetry(
        id = index.toLong(),
        runId = 1788023993636L,
        stepIndex = index,
        totalSteps = 10,
        packageName = "com.example.$index",
        modeKey = "ADVANCED_FULL_COMPILE",
        forceOptimize = false,
        status = if (attempted) "SUCCEEDED" else "SKIPPED_NOT_APPLICABLE",
        outcome = outcome,
        requestedFilter = "speed",
        beforeFilter = "verify",
        afterFilter = "speed",
        artStatus = "speed",
        artFinalStatus = "speed",
        artSizeBytes = null,
        artSizeBeforeBytes = null,
        androidBuild = "device-test",
        artModuleVersion = "2.1.0",
        packageLastUpdateTimeMs = null,
        stableOsAdjusted = false,
        exitCode = 0,
        stdout = "Success",
        stderr = "",
        displayCommand = if (attempted) "cmd package compile -m speed com.example.$index" else null,
        durationMs = 1L,
        storageBefore = null,
        storageAfter = null,
        verificationSource = "cmd-package-dump",
        createdAtMs = 1L,
        updatedAtMs = 2L
    )
}

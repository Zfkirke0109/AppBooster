package com.tony.appbooster.data.repository

import com.tony.appbooster.data.local.optimization.OptimizationStepEntity
import com.tony.appbooster.data.local.optimization.OptimizationStepStatus
import com.tony.appbooster.domain.model.telemetry.OptimizationRunStatus
import com.tony.appbooster.domain.model.telemetry.OptimizationRunTelemetry
import com.tony.appbooster.domain.model.telemetry.StorageSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeIdentityPolicyTest {
    @Test
    fun `forced plan with matching runtime can reuse its recorded work`() {
        assertTrue(canReusePlan(listOf(step(), step().copy(id = 2L, status = OptimizationStepStatus.PENDING))))
    }

    @Test
    fun `changed Android build or ART runtime rejects recorded work`() {
        assertFalse(canReusePlan(listOf(step().copy(androidBuild = "older-build"))))
        assertFalse(canReusePlan(listOf(step().copy(artModuleVersion = "older-art"))))
    }

    @Test
    fun `missing recorded runtime identity cannot establish an unchanged runtime`() {
        listOf(null, "", "unknown").forEach { missing ->
            assertFalse(canReusePlan(listOf(step().copy(androidBuild = missing))))
            assertFalse(canReusePlan(listOf(step().copy(artModuleVersion = missing))))
        }
    }

    @Test
    fun `matching unknown runtime values do not validate a resume`() {
        assertFalse(ResumeIdentityPolicy.canReusePlan(
            steps = listOf(step().copy(androidBuild = "", artModuleVersion = "unknown")),
            telemetry = null,
            currentAndroidBuild = "",
            currentArtModuleVersion = "unknown"
        ))
    }

    @Test
    fun `analysis skips on any step require fresh package selection`() {
        assertFalse(canReusePlan(listOf(step(), step().copy(id = 2L, skippedCount = 1))))
    }

    @Test
    fun `telemetry with packages absent from the recorded steps cannot be resumed`() {
        assertFalse(canReusePlan(listOf(step()), telemetry().copy(totalTargetedCount = 2)))
        assertFalse(canReusePlan(listOf(step()), telemetry().copy(alreadyOptimizedCount = 1)))
        assertFalse(canReusePlan(listOf(step()), telemetry().copy(skippedNoProfileCount = 1)))
    }

    @Test
    fun `telemetry runtime must agree with current runtime and stored steps`() {
        assertTrue(canReusePlan(listOf(step()), telemetry()))
        assertFalse(canReusePlan(listOf(step()), telemetry().copy(androidBuild = "older-build")))
        assertFalse(canReusePlan(listOf(step()), telemetry().copy(artModuleVersion = "unknown")))
    }

    @Test
    fun `unchanged installed package identity permits reuse of a terminal result`() {
        assertTrue(ResumeIdentityPolicy.hasSamePackageIdentity(123L, 123L))
    }

    @Test
    fun `updated or removed package invalidates a terminal result`() {
        assertFalse(ResumeIdentityPolicy.hasSamePackageIdentity(123L, 456L))
        assertFalse(ResumeIdentityPolicy.hasSamePackageIdentity(123L, null))
    }

    @Test
    fun `missing or invalid recorded update identity cannot establish unchanged package`() {
        assertFalse(ResumeIdentityPolicy.hasSamePackageIdentity(null, 123L))
        assertFalse(ResumeIdentityPolicy.hasSamePackageIdentity(null, null))
        assertFalse(ResumeIdentityPolicy.hasSamePackageIdentity(0L, 0L))
    }

    private fun canReusePlan(
        steps: List<OptimizationStepEntity>,
        telemetry: OptimizationRunTelemetry? = null
    ) = ResumeIdentityPolicy.canReusePlan(steps, telemetry, "current-build", "current-art")

    private fun step() = OptimizationStepEntity(
        id = 1L,
        runId = 77L,
        stepIndex = 0,
        totalSteps = 2,
        skippedCount = 0,
        packageName = "com.example.done",
        mode = "SPEED_PROFILE",
        forceOptimize = true,
        status = OptimizationStepStatus.SUCCEEDED,
        androidBuild = "current-build",
        artModuleVersion = "current-art",
        packageLastUpdateTimeMs = 123L,
        createdAtMs = 1L
    )

    private fun telemetry() = OptimizationRunTelemetry(
        runId = 77L,
        modeKey = "SPEED_PROFILE",
        requestedCompilerFilter = "speed-profile",
        fullDexoptScope = false,
        forceOptimize = true,
        status = OptimizationRunStatus.PAUSED,
        startedAtMs = 1L,
        totalTargetedCount = 1,
        storageBefore = StorageSnapshot(100L, 80L, 5L, 1L),
        appVersionName = "test",
        appVersionCode = 1L,
        deviceManufacturer = "samsung",
        deviceModel = "test",
        sdkInt = 37,
        buildFingerprint = "current-build",
        artModuleVersion = "current-art"
    )
}

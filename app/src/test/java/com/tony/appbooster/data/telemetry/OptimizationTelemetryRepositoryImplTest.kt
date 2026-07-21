package com.tony.appbooster.data.telemetry

import com.tony.appbooster.data.local.optimization.OptimizationRunDao
import com.tony.appbooster.data.local.optimization.OptimizationRunEntity
import com.tony.appbooster.data.local.optimization.OptimizationStepDao
import com.tony.appbooster.data.local.optimization.OptimizationStepEntity
import com.tony.appbooster.data.local.optimization.OptimizationStepStatus
import com.tony.appbooster.domain.model.telemetry.OptimizationRunStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class OptimizationTelemetryRepositoryImplTest {
    private val runDao = mockk<OptimizationRunDao>()
    private val stepDao = mockk<OptimizationStepDao>()
    private val repository = OptimizationTelemetryRepositoryImpl(runDao, stepDao)

    @Test
    fun `Room telemetry maps counters storage and verification without relabeling`() = runTest {
        val runId = 42L
        coEvery { runDao.getRun(runId) } returns OptimizationRunEntity(
            runId = runId,
            modeKey = "FULL_DEX2OAT_SPEED",
            requestedCompilerFilter = "speed",
            fullDexoptScope = true,
            forceOptimize = true,
            status = OptimizationRunStatus.COMPLETED_WITH_ISSUES.name,
            statusMessage = "one package unverified",
            startedAtMs = 1_000L,
            finishedAtMs = 2_000L,
            totalTargetedCount = 4,
            processedCount = 4,
            optimizedSucceededCount = 2,
            alreadyOptimizedCount = 0,
            skippedNoProfileCount = 0,
            failedOrRefusedCount = 1,
            unverifiedCount = 1,
            storageTotalBeforeBytes = 100_000L,
            storageAvailableBeforeBytes = 80_000L,
            storageReserveBytes = 5_000L,
            storageCapturedBeforeAtMs = 900L,
            storageTotalAfterBytes = 100_000L,
            storageAvailableAfterBytes = 79_000L,
            storageCapturedAfterAtMs = 2_100L,
            appVersionName = "1.7.0",
            appVersionCode = 10_700L,
            deviceManufacturer = "Samsung",
            deviceModel = "SM-S918U1",
            sdkInt = 36,
            buildFingerprint = "test/fingerprint",
            threadPolicy = "package-manager-managed"
        )
        coEvery { stepDao.getStepsForRun(runId) } returns listOf(
            OptimizationStepEntity(
                id = 7L,
                runId = runId,
                stepIndex = 0,
                totalSteps = 4,
                skippedCount = 0,
                packageName = "com.example.app",
                mode = "FULL_DEX2OAT_SPEED",
                forceOptimize = true,
                status = OptimizationStepStatus.UNVERIFIED,
                beforeFilter = "verify",
                afterFilter = "speed-profile",
                exitCode = 0,
                stdout = "Success",
                stderr = "filter did not match",
                displayCommand = "cmd package compile -m speed -f --full com.example.app",
                durationMs = 321L,
                storageTotalBeforeBytes = 100_000L,
                storageAvailableBeforeBytes = 80_000L,
                storageReserveBytes = 5_000L,
                storageCapturedBeforeAtMs = 1_100L,
                storageTotalAfterBytes = 100_000L,
                storageAvailableAfterBytes = 79_500L,
                storageCapturedAfterAtMs = 1_500L,
                verificationSource = "cmd-package-dump",
                createdAtMs = 1_050L,
                updatedAtMs = 1_500L
            )
        )

        val run = checkNotNull(repository.getRun(runId))
        val step = repository.getSteps(runId).single()

        assertEquals(OptimizationRunStatus.COMPLETED_WITH_ISSUES, run.status)
        assertEquals(2, run.optimizedSucceededCount)
        assertEquals(1, run.failedOrRefusedCount)
        assertEquals(1, run.unverifiedCount)
        assertEquals(80_000L, run.storageBefore.availableBytes)
        assertEquals(79_000L, run.storageAfter?.availableBytes)
        assertEquals(OptimizationStepStatus.UNVERIFIED, step.status)
        assertEquals("speed-profile", step.afterFilter)
        assertEquals("cmd-package-dump", step.verificationSource)
        assertEquals(79_500L, step.storageAfter?.availableBytes)
    }

    @Test
    fun `resuming a run clears stale export metadata`() = runTest {
        val runId = 84L
        val existing = OptimizationRunEntity(
            runId = runId,
            modeKey = "SPEED_PROFILE",
            requestedCompilerFilter = "speed-profile",
            fullDexoptScope = false,
            forceOptimize = false,
            status = OptimizationRunStatus.PAUSED.name,
            statusMessage = "storage reserve",
            startedAtMs = 1_000L,
            finishedAtMs = null,
            storageTotalBeforeBytes = 100_000L,
            storageAvailableBeforeBytes = 80_000L,
            storageReserveBytes = 5_000L,
            storageCapturedBeforeAtMs = 900L,
            appVersionName = "1.7.0",
            appVersionCode = 10_700L,
            deviceManufacturer = "Samsung",
            deviceModel = "SM-S918U1",
            sdkInt = 36,
            buildFingerprint = "test/fingerprint",
            threadPolicy = "package-manager-managed",
            exportUri = "content://telemetry/stale",
            exportError = "stale error",
            exportedAtMs = 2_000L
        )
        val saved = slot<OptimizationRunEntity>()
        coEvery { runDao.getRun(runId) } returns existing
        coEvery { runDao.upsert(capture(saved)) } returns Unit

        repository.startOrResumeRun(
            runId = runId,
            mode = com.tony.appbooster.domain.model.settings.AppOptimizationType.SPEED_PROFILE,
            forceOptimize = false,
            storageBefore = com.tony.appbooster.domain.model.telemetry.StorageSnapshot(
                totalBytes = 100_000L,
                availableBytes = 79_000L,
                reserveBytes = 5_000L,
                capturedAtMs = 2_100L
            )
        )

        coVerify(exactly = 1) { runDao.upsert(any()) }
        assertEquals(OptimizationRunStatus.RUNNING.name, saved.captured.status)
        assertEquals(null, saved.captured.exportUri)
        assertEquals(null, saved.captured.exportError)
        assertEquals(null, saved.captured.exportedAtMs)
    }
}

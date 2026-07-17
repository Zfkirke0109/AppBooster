package com.tony.appbooster.domain.usecase.telemetry

import com.tony.appbooster.domain.model.telemetry.OptimizationRunStatus
import com.tony.appbooster.domain.model.telemetry.OptimizationRunTelemetry
import com.tony.appbooster.domain.model.telemetry.StorageSnapshot
import com.tony.appbooster.domain.model.telemetry.TelemetryExportResult
import com.tony.appbooster.domain.repository.OptimizationTelemetryRepository
import com.tony.appbooster.domain.service.TelemetryExporter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryLatestTelemetryExportUseCaseTest {
    private val repository: OptimizationTelemetryRepository = mockk()
    private val exporter: TelemetryExporter = mockk()
    private val useCase = RetryLatestTelemetryExportUseCase(repository, exporter)

    @Test
    fun `no latest run returns visible failure without exporting`() = runTest {
        coEvery { repository.getLatestRun() } returns null

        val result = useCase()

        assertTrue(result is TelemetryExportResult.Failure)
        coVerify(exactly = 0) { exporter.export(any(), any()) }
    }

    @Test
    fun `running latest run refuses export`() = runTest {
        coEvery { repository.getLatestRun() } returns telemetryRun(OptimizationRunStatus.RUNNING)

        val result = useCase()

        assertTrue(result is TelemetryExportResult.Failure)
        coVerify(exactly = 0) { exporter.export(any(), any()) }
    }

    @Test
    fun `terminal latest run forces a fresh export`() = runTest {
        val run = telemetryRun(OptimizationRunStatus.COMPLETED)
        val expected = TelemetryExportResult.Success("content://telemetry/1", 5L)
        coEvery { repository.getLatestRun() } returns run
        coEvery { exporter.export(run.runId, overwriteExisting = true) } returns expected

        val result = useCase()

        assertEquals(expected, result)
        coVerify(exactly = 1) { exporter.export(run.runId, overwriteExisting = true) }
    }

    @Test
    fun `export failure remains separate from terminal optimization status`() = runTest {
        val run = telemetryRun(OptimizationRunStatus.COMPLETED_WITH_ISSUES)
        val expected = TelemetryExportResult.Failure("Downloads provider unavailable")
        coEvery { repository.getLatestRun() } returns run
        coEvery { exporter.export(run.runId, overwriteExisting = true) } returns expected

        val result = useCase()

        assertEquals(expected, result)
        assertEquals(OptimizationRunStatus.COMPLETED_WITH_ISSUES, run.status)
    }

    private fun telemetryRun(status: OptimizationRunStatus) = OptimizationRunTelemetry(
        runId = 1L,
        modeKey = "SPEED_PROFILE",
        requestedCompilerFilter = "speed-profile",
        fullDexoptScope = false,
        forceOptimize = false,
        status = status,
        startedAtMs = 1L,
        storageBefore = StorageSnapshot(100L, 50L, 10L, 1L),
        appVersionName = "1.7.0",
        appVersionCode = 10700L,
        deviceManufacturer = "Samsung",
        deviceModel = "SM-S918U1",
        sdkInt = 36,
        buildFingerprint = "test"
    )
}

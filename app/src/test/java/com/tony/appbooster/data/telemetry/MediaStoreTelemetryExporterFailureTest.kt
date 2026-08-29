package com.tony.appbooster.data.telemetry

import android.content.Context
import com.tony.appbooster.domain.model.telemetry.OptimizationRunStatus
import com.tony.appbooster.domain.model.telemetry.OptimizationRunTelemetry
import com.tony.appbooster.domain.model.telemetry.StorageSnapshot
import com.tony.appbooster.domain.model.telemetry.TelemetryExportResult
import com.tony.appbooster.domain.repository.OptimizationTelemetryRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaStoreTelemetryExporterFailureTest {
    private val context = mockk<Context>(relaxed = true)
    private val repository = mockk<OptimizationTelemetryRepository>()
    private val exporter = MediaStoreTelemetryExporter(context, repository, Dispatchers.Unconfined)

    @Test
    fun `failed export records the error and still prunes retained runs`() = runTest {
        val run = terminalRun()
        coEvery { repository.getRun(run.runId) } returns run
        coEvery { repository.getSteps(run.runId) } throws IOException("read failed")
        coEvery { repository.recordExportResult(run.runId, any()) } just Runs
        coEvery {
            repository.pruneToLatest(MediaStoreTelemetryExporter.RETAINED_RUN_COUNT)
        } just Runs

        val result = exporter.export(run.runId)

        assertTrue(result is TelemetryExportResult.Failure)
        coVerify(exactly = 1) {
            repository.recordExportResult(
                run.runId,
                match { it is TelemetryExportResult.Failure && it.message == "read failed" }
            )
        }
        coVerify(exactly = 1) {
            repository.pruneToLatest(MediaStoreTelemetryExporter.RETAINED_RUN_COUNT)
        }
    }

    @Test
    fun `cached export still prunes retained runs`() = runTest {
        val run = terminalRun().copy(
            exportUri = "content://telemetry/existing",
            exportedAtMs = 1234L
        )
        coEvery { repository.getRun(run.runId) } returns run
        coEvery {
            repository.pruneToLatest(MediaStoreTelemetryExporter.RETAINED_RUN_COUNT)
        } just Runs

        val result = exporter.export(run.runId)

        assertTrue(result is TelemetryExportResult.Success)
        coVerify(exactly = 1) {
            repository.pruneToLatest(MediaStoreTelemetryExporter.RETAINED_RUN_COUNT)
        }
        coVerify(exactly = 0) { repository.getSteps(any()) }
    }

    private fun terminalRun() = OptimizationRunTelemetry(
        runId = 42L,
        modeKey = "SPEED_PROFILE",
        requestedCompilerFilter = "speed-profile",
        fullDexoptScope = false,
        forceOptimize = false,
        status = OptimizationRunStatus.COMPLETED,
        startedAtMs = 1L,
        finishedAtMs = 2L,
        storageBefore = StorageSnapshot(100L, 80L, 5L, 1L),
        storageAfter = StorageSnapshot(100L, 70L, 5L, 2L),
        appVersionName = "1.7.0",
        appVersionCode = 10_700L,
        deviceManufacturer = "Samsung",
        deviceModel = "SM-S918U1",
        sdkInt = 36,
        buildFingerprint = "test/fingerprint"
    )
}

package com.tony.appbooster.data.telemetry

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tony.appbooster.domain.model.common.OptimizationProgress
import com.tony.appbooster.domain.model.settings.AppOptimizationType
import com.tony.appbooster.domain.model.telemetry.OptimizationRunStatus
import com.tony.appbooster.domain.model.telemetry.OptimizationRunTelemetry
import com.tony.appbooster.domain.model.telemetry.OptimizationStepTelemetry
import com.tony.appbooster.domain.model.telemetry.StorageSnapshot
import com.tony.appbooster.domain.model.telemetry.TelemetryExportResult
import com.tony.appbooster.domain.repository.OptimizationTelemetryRepository
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaStoreTelemetryExporterTest {
    @Test
    fun terminalRunExportsStructuredJsonAndTruncatesCommandOutput() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val run = terminalRun()
        val repository = FakeTelemetryRepository(run, listOf(stepWithLargeOutput(run.runId)))
        val exporter = MediaStoreTelemetryExporter(context, repository, Dispatchers.IO)

        val result = exporter.export(run.runId)

        assertTrue(result is TelemetryExportResult.Success)
        val uri = Uri.parse((result as TelemetryExportResult.Success).uri)
        try {
            val json = context.contentResolver.openInputStream(uri)?.use { input ->
                BufferedReader(InputStreamReader(input)).readText()
            }.orEmpty()
            assertTrue(json.contains("\"schemaVersion\": 2"))
            assertTrue(json.contains("\"applicationId\": \"com.zfkirke0109.galaxyoptidroid\""))
            assertTrue(json.contains("\"threadPolicy\": \"package-manager-managed\""))
            assertTrue(json.contains("\"packageName\": \"com.example.telemetry\""))
            assertTrue(json.contains("\"stdoutTruncated\": true"))
            assertTrue(json.contains("Device-volume free-space observation"))
        } finally {
            context.contentResolver.delete(uri, null, null)
        }
    }

    private fun terminalRun() = OptimizationRunTelemetry(
        runId = System.currentTimeMillis(),
        modeKey = AppOptimizationType.SPEED_PROFILE.value,
        requestedCompilerFilter = "speed-profile",
        fullDexoptScope = false,
        forceOptimize = false,
        status = OptimizationRunStatus.COMPLETED,
        startedAtMs = 1L,
        finishedAtMs = 2L,
        totalTargetedCount = 1,
        processedCount = 1,
        optimizedSucceededCount = 1,
        storageBefore = StorageSnapshot(100L, 80L, 5L, 1L),
        storageAfter = StorageSnapshot(100L, 70L, 5L, 2L),
        appVersionName = "1.7.0",
        appVersionCode = 10700L,
        deviceManufacturer = "Samsung",
        deviceModel = "SM-S918U1",
        sdkInt = 36,
        buildFingerprint = "instrumented-test"
    )

    private fun stepWithLargeOutput(runId: Long) = OptimizationStepTelemetry(
        id = 1L,
        runId = runId,
        stepIndex = 0,
        totalSteps = 1,
        packageName = "com.example.telemetry",
        modeKey = AppOptimizationType.SPEED_PROFILE.value,
        forceOptimize = false,
        status = "SUCCEEDED",
        outcome = null,
        requestedFilter = "speed-profile",
        beforeFilter = "verify",
        afterFilter = "speed-profile",
        artStatus = "PERFORMED",
        artFinalStatus = "PERFORMED",
        artSizeBytes = 20L,
        artSizeBeforeBytes = 10L,
        androidBuild = "instrumented-test",
        artModuleVersion = "test-art",
        packageLastUpdateTimeMs = 1L,
        stableOsAdjusted = false,
        exitCode = 0,
        stdout = "x".repeat(TelemetryTextLimiter.DEFAULT_MAX_BYTES + 1),
        stderr = "",
        displayCommand = "cmd package compile -m speed-profile -f com.example.telemetry",
        durationMs = 123L,
        storageBefore = StorageSnapshot(100L, 80L, 5L, 1L),
        storageAfter = StorageSnapshot(100L, 70L, 5L, 2L),
        verificationSource = "cmd-package-dump",
        createdAtMs = 1L,
        updatedAtMs = 2L
    )

    private class FakeTelemetryRepository(
        private val run: OptimizationRunTelemetry,
        private val steps: List<OptimizationStepTelemetry>
    ) : OptimizationTelemetryRepository {
        override fun observeLatestRun(): Flow<OptimizationRunTelemetry?> = flowOf(run)
        override suspend fun getRun(runId: Long) = run.takeIf { it.runId == runId }
        override suspend fun getLatestRun() = run
        override suspend fun getSteps(runId: Long) = steps.filter { it.runId == runId }
        override suspend fun startOrResumeRun(
            runId: Long,
            mode: AppOptimizationType,
            forceOptimize: Boolean,
            storageBefore: StorageSnapshot
        ) = Unit
        override suspend fun updatePlanCounts(
            runId: Long,
            totalTargetedCount: Int,
            alreadyOptimizedCount: Int,
            skippedNoProfileCount: Int
        ) = Unit
        override suspend fun updateProgress(runId: Long, progress: OptimizationProgress) = Unit
        override suspend fun recordStepStarted(
            stepId: Long,
            displayCommand: String,
            storageBefore: StorageSnapshot
        ) = Unit
        override suspend fun recordStepFinished(
            stepId: Long,
            durationMs: Long,
            storageAfter: StorageSnapshot,
            verificationSource: String
        ) = Unit
        override suspend fun finishRun(
            runId: Long,
            status: OptimizationRunStatus,
            statusMessage: String?,
            progress: OptimizationProgress,
            storageAfter: StorageSnapshot
        ) = Unit
        override suspend fun recordExportResult(runId: Long, result: TelemetryExportResult) = Unit
        override suspend fun pruneToLatest(retainCount: Int) = Unit
    }
}

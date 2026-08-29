package com.tony.appbooster.data.telemetry

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.JsonWriter
import androidx.annotation.RequiresApi
import com.tony.appbooster.BuildConfig
import com.tony.appbooster.di.AdbIoDispatcher
import com.tony.appbooster.domain.model.telemetry.OptimizationRunTelemetry
import com.tony.appbooster.domain.model.telemetry.OptimizationStepOutcome
import com.tony.appbooster.domain.model.telemetry.OptimizationStepTelemetry
import com.tony.appbooster.domain.model.telemetry.StorageSnapshot
import com.tony.appbooster.domain.model.telemetry.TelemetryExportResult
import com.tony.appbooster.domain.repository.OptimizationTelemetryRepository
import com.tony.appbooster.domain.service.TelemetryExporter
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

@Singleton
class MediaStoreTelemetryExporter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: OptimizationTelemetryRepository,
    @param:AdbIoDispatcher private val ioDispatcher: CoroutineDispatcher
) : TelemetryExporter {
    override suspend fun export(
        runId: Long,
        overwriteExisting: Boolean
    ): TelemetryExportResult = withContext(ioDispatcher) {
        val run = repository.getRun(runId)
            ?: return@withContext TelemetryExportResult.Failure("Telemetry run $runId was not found.")
        if (!run.status.isTerminal) {
            return@withContext TelemetryExportResult.Failure("Telemetry run $runId is not finished.")
        }
        if (!overwriteExisting && run.exportUri != null && run.exportedAtMs != null) {
            val existingResult = TelemetryExportResult.Success(run.exportUri, run.exportedAtMs)
            runCatching { repository.pruneToLatest(RETAINED_RUN_COUNT) }
            return@withContext existingResult
        }

        val exportedAtMs = System.currentTimeMillis()
        val fileName = "galaxy-optidroid-run-${run.runId}-$exportedAtMs.json"
        val result = runCatching {
            val steps = repository.getSteps(runId)
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                exportToMediaStore(fileName) { output ->
                    writeJson(output, run, steps, exportedAtMs)
                }
            } else {
                exportToAppExternalFiles(fileName) { output ->
                    writeJson(output, run, steps, exportedAtMs)
                }
            }
            TelemetryExportResult.Success(uri = uri, exportedAtMs = exportedAtMs)
        }.getOrElse { throwable ->
            TelemetryExportResult.Failure(
                throwable.message ?: "Telemetry export failed."
            )
        }

        runCatching { repository.recordExportResult(runId, result) }
        runCatching { repository.pruneToLatest(RETAINED_RUN_COUNT) }
        result
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun exportToMediaStore(
        fileName: String,
        write: (OutputStream) -> Unit
    ): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, JSON_MIME_TYPE)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                "${Environment.DIRECTORY_DOWNLOADS}/$EXPORT_DIRECTORY"
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Unable to create telemetry export in Downloads.")

        try {
            resolver.openOutputStream(uri, "w")?.use(write)
                ?: error("Unable to open telemetry export output stream.")
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null
            )
            return uri.toString()
        } catch (throwable: Throwable) {
            resolver.delete(uri, null, null)
            throw throwable
        }
    }

    private fun exportToAppExternalFiles(
        fileName: String,
        write: (OutputStream) -> Unit
    ): String {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: context.filesDir
        val directory = File(root, EXPORT_DIRECTORY)
        check(directory.exists() || directory.mkdirs()) {
            "Unable to create app telemetry directory."
        }

        val target = File(directory, fileName)
        val temporary = File(directory, ".$fileName.tmp")
        try {
            temporary.outputStream().buffered().use(write)
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                check(temporary.delete()) { "Unable to finalize telemetry export." }
            }
            return Uri.fromFile(target).toString()
        } catch (throwable: Throwable) {
            temporary.delete()
            throw throwable
        }
    }

    private fun writeJson(
        output: OutputStream,
        run: OptimizationRunTelemetry,
        steps: List<OptimizationStepTelemetry>,
        exportedAtMs: Long
    ) {
        val totals = OptimizationTelemetrySummary.from(run, steps)
        JsonWriter(OutputStreamWriter(output, Charsets.UTF_8)).use { writer ->
            writer.setIndent("  ")
            writer.beginObject()
            writer.name("schemaVersion").value(JSON_SCHEMA_VERSION)
            writer.name("exportedAtUtc").value(Instant.ofEpochMilli(exportedAtMs).toString())

            writer.name("app").beginObject()
            writer.name("applicationId").value(BuildConfig.APPLICATION_ID)
            writer.name("versionName").value(run.appVersionName)
            writer.name("versionCode").value(run.appVersionCode)
            writer.endObject()

            writer.name("device").beginObject()
            writer.name("manufacturer").value(run.deviceManufacturer)
            writer.name("model").value(run.deviceModel)
            writer.name("sdkInt").value(run.sdkInt.toLong())
            writer.name("buildFingerprint").value(run.buildFingerprint)
            writer.name("androidBuild").value(run.androidBuild)
            writer.name("artModuleVersion").value(run.artModuleVersion)
            writer.endObject()

            writer.name("run").beginObject()
            writer.name("runId").value(run.runId)
            writer.name("modeKey").value(run.modeKey)
            writer.name("requestedCompilerFilter").value(run.requestedCompilerFilter)
            writer.name("fullDexoptScope").value(run.fullDexoptScope)
            writer.name("forceOptimize").value(run.forceOptimize)
            writer.name("status").value(run.status.name)
            writer.name("statusMessage").nullableValue(run.statusMessage)
            writer.name("startedAtUtc").value(Instant.ofEpochMilli(run.startedAtMs).toString())
            writer.name("finishedAtUtc").nullableInstant(run.finishedAtMs)
            writer.name("totalTargetedCount").value(run.totalTargetedCount.toLong())
            writer.name("finalTotals").beginObject()
            writer.name("targeted").value(totals.targeted.toLong())
            writer.name("attempted").value(totals.attempted.toLong())
            writer.name("success").value(totals.success.toLong())
            writer.name("skipped").value(totals.skipped.toLong())
            writer.name("failed").value(totals.failed.toLong())
            writer.name("unverified").value(totals.unverified.toLong())
            writer.name("canceled").value(totals.canceled.toLong())
            writer.name("accounted").value(totals.accounted.toLong())
            writer.name("reconciled").value(totals.reconciled)
            writer.endObject()
            writer.name("processedCount").value(run.processedCount.toLong())
            writer.name("optimizedSucceededCount").value(run.optimizedSucceededCount.toLong())
            writer.name("alreadyOptimizedCount").value(run.alreadyOptimizedCount.toLong())
            writer.name("skippedNoProfileCount").value(run.skippedNoProfileCount.toLong())
            writer.name("failedOrRefusedCount").value(run.failedOrRefusedCount.toLong())
            writer.name("unverifiedCount").value(totals.unverified.toLong())
            writer.name("osAdjustedFilterCount").value(run.osAdjustedFilterCount.toLong())
            writer.name("skippedNotApplicableCount").value(run.skippedNotApplicableCount.toLong())
            writer.name("verificationUnavailableCount").value(run.verificationUnavailableCount.toLong())
            writer.name("canceledCount").value(run.canceledCount.toLong())
            writer.name("artStorageDeltaBytes").value(run.artStorageDeltaBytes)
            writer.name("threadPolicy").value(run.threadPolicy)
            writer.name("storageBefore").storageSnapshot(run.storageBefore)
            writer.name("storageAfter").nullableStorageSnapshot(run.storageAfter)
            writer.name("storageMeasurementNote").value(STORAGE_MEASUREMENT_NOTE)
            writer.endObject()

            writer.name("steps").beginArray()
            steps.forEach { step -> writer.optimizationStep(step) }
            writer.endArray()
            writer.endObject()
        }
    }

    private fun JsonWriter.optimizationStep(step: OptimizationStepTelemetry) {
        val boundedStdout = TelemetryTextLimiter.limit(step.stdout)
        val boundedStderr = TelemetryTextLimiter.limit(step.stderr)
        beginObject()
        name("stepIndex").value(step.stepIndex.toLong())
        name("totalSteps").value(step.totalSteps.toLong())
        name("packageName").value(step.packageName)
        name("modeKey").value(step.modeKey)
        name("forceOptimize").value(step.forceOptimize)
        name("status").value(step.status)
        name("outcome").nullableValue(step.outcome?.name)
        name("resultClass").nullableValue(step.outcome?.exportResultClass())
        name("attempted").value(!step.displayCommand.isNullOrBlank())
        name("verifiedRequestedFilter").value(
            step.outcome == OptimizationStepOutcome.VERIFIED_REQUESTED_FILTER
        )
        name("requestedFilter").nullableValue(step.requestedFilter)
        name("displayCommand").nullableValue(step.displayCommand)
        name("beforeFilter").nullableValue(step.beforeFilter)
        name("afterFilter").nullableValue(step.afterFilter)
        name("artStatus").nullableValue(step.artStatus)
        name("artFinalStatus").nullableValue(step.artFinalStatus)
        name("artSizeBytes").nullableLong(step.artSizeBytes)
        name("artSizeBeforeBytes").nullableLong(step.artSizeBeforeBytes)
        name("androidBuild").nullableValue(step.androidBuild)
        name("artModuleVersion").nullableValue(step.artModuleVersion)
        name("packageLastUpdateTimeMs").nullableLong(step.packageLastUpdateTimeMs)
        name("stableOsAdjusted").value(step.stableOsAdjusted)
        name("exitCode").nullableLong(step.exitCode?.toLong())
        name("durationMs").nullableLong(step.durationMs)
        name("verificationSource").nullableValue(step.verificationSource)
        name("storageBefore").nullableStorageSnapshot(step.storageBefore)
        name("storageAfter").nullableStorageSnapshot(step.storageAfter)
        name("stdout").nullableValue(boundedStdout.value)
        name("stdoutTruncated").value(boundedStdout.truncated)
        name("stderr").nullableValue(boundedStderr.value)
        name("stderrTruncated").value(boundedStderr.truncated)
        endObject()
    }

    private fun JsonWriter.storageSnapshot(snapshot: StorageSnapshot) {
        beginObject()
        name("totalBytes").value(snapshot.totalBytes)
        name("availableBytes").value(snapshot.availableBytes)
        name("reserveBytes").value(snapshot.reserveBytes)
        name("capturedAtUtc").value(Instant.ofEpochMilli(snapshot.capturedAtMs).toString())
        endObject()
    }

    private fun JsonWriter.nullableStorageSnapshot(snapshot: StorageSnapshot?) {
        if (snapshot == null) nullValue() else storageSnapshot(snapshot)
    }

    private fun JsonWriter.nullableValue(value: String?) {
        if (value == null) nullValue() else value(value)
    }

    private fun JsonWriter.nullableLong(value: Long?) {
        if (value == null) nullValue() else value(value)
    }

    private fun JsonWriter.nullableInstant(value: Long?) {
        if (value == null) nullValue() else value(Instant.ofEpochMilli(value).toString())
    }

    companion object {
        const val JSON_SCHEMA_VERSION = 3L
        const val RETAINED_RUN_COUNT = 20
        private const val JSON_MIME_TYPE = "application/json"
        private const val EXPORT_DIRECTORY = "Galaxy OptiDroid/Telemetry"
        private const val STORAGE_MEASUREMENT_NOTE =
            "Device-volume free-space observation; not per-package DEX/VDEX/ART size."
    }
}

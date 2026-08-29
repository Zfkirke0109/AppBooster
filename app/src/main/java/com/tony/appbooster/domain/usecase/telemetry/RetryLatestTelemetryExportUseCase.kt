package com.tony.appbooster.domain.usecase.telemetry

import com.tony.appbooster.domain.model.telemetry.TelemetryExportResult
import com.tony.appbooster.domain.repository.OptimizationTelemetryRepository
import com.tony.appbooster.domain.service.TelemetryExporter

class RetryLatestTelemetryExportUseCase(
    private val repository: OptimizationTelemetryRepository,
    private val exporter: TelemetryExporter
) {
    suspend operator fun invoke(): TelemetryExportResult {
        val run = repository.getLatestRun()
            ?: return TelemetryExportResult.Failure("No optimization telemetry is available yet.")
        if (!run.status.isTerminal) {
            return TelemetryExportResult.Failure("The latest optimization run has not finished yet.")
        }
        return exporter.export(run.runId, overwriteExisting = true)
    }
}

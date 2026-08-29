package com.tony.appbooster.domain.service

import com.tony.appbooster.domain.model.telemetry.TelemetryExportResult

interface TelemetryExporter {
    suspend fun export(runId: Long, overwriteExisting: Boolean = false): TelemetryExportResult
}

package com.tony.appbooster.domain.model.telemetry

sealed interface TelemetryExportResult {
    data class Success(
        val uri: String,
        val exportedAtMs: Long
    ) : TelemetryExportResult

    data class Failure(
        val message: String
    ) : TelemetryExportResult
}

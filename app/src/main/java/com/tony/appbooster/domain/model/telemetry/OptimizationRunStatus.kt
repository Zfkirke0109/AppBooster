package com.tony.appbooster.domain.model.telemetry

enum class OptimizationRunStatus {
    RUNNING,
    PAUSED,
    COMPLETED,
    COMPLETED_WITH_ISSUES,
    CANCELED,
    FAILED;

    val isTerminal: Boolean
        get() = this in setOf(COMPLETED, COMPLETED_WITH_ISSUES, CANCELED, FAILED)
}

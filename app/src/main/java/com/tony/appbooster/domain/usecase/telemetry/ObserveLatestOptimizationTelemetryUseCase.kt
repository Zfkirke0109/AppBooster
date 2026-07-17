package com.tony.appbooster.domain.usecase.telemetry

import com.tony.appbooster.domain.repository.OptimizationTelemetryRepository

class ObserveLatestOptimizationTelemetryUseCase(
    private val repository: OptimizationTelemetryRepository
) {
    operator fun invoke() = repository.observeLatestRun()
}

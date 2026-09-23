package com.tony.appbooster.domain.repository

import com.tony.appbooster.domain.model.performance.*
import kotlinx.coroutines.flow.StateFlow

/** Selected-app startup measurement, independent of compiler success counters. */
interface PerformanceRepository {
    /** Durable latest measurement and live phase progress. */
    val session: StateFlow<PerformanceSession?>
    /** Restore the saved session; incomplete work remains explicitly interrupted. */
    suspend fun load()
    /** User-installed launchable apps in this profile; excludes OptiDroid itself. */
    suspend fun apps(): List<MeasurementApp>
    /** Start a new explicitly selected app session, replacing the previous capture. */
    suspend fun select(app: MeasurementApp)
    /** Execute BEFORE, COMPILE or AFTER for the expected session, recording failures. */
    suspend fun execute(action: String, sessionId: Long, operationId: String): Result<Unit>
    /** Export the raw samples and compiler evidence as versioned JSON. */
    suspend fun export(): String
}

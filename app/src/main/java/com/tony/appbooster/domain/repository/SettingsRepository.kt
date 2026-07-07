package com.tony.appbooster.domain.repository

import com.tony.appbooster.domain.model.common.Resource
import com.tony.appbooster.domain.model.settings.AppOptimizationType
import kotlinx.coroutines.flow.Flow

/**
 * Provides access to user-facing runtime configuration.
 *
 * Business purpose:
 * - Persist and observe the selected optimization mode.
 *
 * Note: Legacy ADB host/port/pairing-code configuration has been removed.
 */
interface SettingsRepository {

    /**
     * Observes the currently selected optimization mode so the UI can react
     * to changes, wrapping every emission into [Resource].
     *
     * @return A cold [Flow] emitting [Resource] instances representing the
     * active [AppOptimizationType].
     */
    fun observeAppOptimizationType(): Flow<Resource<AppOptimizationType>>

    fun observeHeavyAppPackages(): Flow<Resource<Set<String>>>

    suspend fun getHeavyAppPackages(): Resource<Set<String>>

    /**
     * Persists the chosen optimization mode to durable storage so it is
     * restored on next launch.
     *
     * @param type The [AppOptimizationType] selected by the user.
     * @return A [Resource] describing the success or error of the operation.
     */
    suspend fun setAppOptimizationType(
        type: AppOptimizationType
    ): Resource<Unit>

    suspend fun setHeavyAppPackages(
        packageNames: Set<String>
    ): Resource<Unit>
}

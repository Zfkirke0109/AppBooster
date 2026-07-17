package com.tony.appbooster.presentation.viewmodel.settings

import com.tony.appbooster.domain.model.common.OptimizationRollbackCandidate
import com.tony.appbooster.domain.model.settings.AppOptimizationType
import com.tony.appbooster.domain.model.shizuku.ShizukuState
import com.tony.appbooster.domain.model.telemetry.OptimizationRunTelemetry

/**
 * UI-specific representation of settings data consumed by the Settings screen,
 * including optimization mode, Shizuku status, and static app metadata.
 *
 * @param appOptimizationType Current optimization mode selected by the user.
 * @param appVersionName Human-readable version name displayed in the App info section.
 * @param appVersionChannel Optional label describing the build channel (e.g. Alpha).
 * @param shizukuState Current state of the Shizuku service and permissions.
 * @return Immutable UI state snapshot for settings presentation logic.
 */
data class SettingsUiState(
    val appOptimizationType: AppOptimizationType = AppOptimizationType.SPEED_PROFILE,
    val heavyAppPackages: Set<String> = emptySet(),
    val heavyAppPackageInput: String = "",
    val rollbackCandidates: List<OptimizationRollbackCandidate> = emptyList(),
    val rollingBackPackageName: String? = null,
    val latestTelemetryRun: OptimizationRunTelemetry? = null,
    val isExportingTelemetry: Boolean = false,
    val appVersionName: String = "",
    val appVersionChannel: String? = null,
    val shizukuState: ShizukuState = ShizukuState.NotRunning
)

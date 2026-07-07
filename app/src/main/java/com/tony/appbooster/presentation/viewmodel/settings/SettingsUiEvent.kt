package com.tony.appbooster.presentation.viewmodel.settings

import com.tony.appbooster.domain.model.settings.AppOptimizationType

/**
 * User intents originating from the Settings screen.
 *
 * Each variant represents a discrete action the user can take on the
 * Settings screen and is dispatched to [SettingsViewModel.handleEvent].
 */
sealed interface SettingsUiEvent {

    /**
     * Requests persisting a new optimization type selected by the user.
     *
     * @property type New optimization mode chosen in the UI.
     */
    data class OnOptimizationTypeSelected(val type: AppOptimizationType) : SettingsUiEvent

    data class OnHeavyAppPackageInputChanged(val packageName: String) : SettingsUiEvent

    data object OnAddHeavyAppPackageClicked : SettingsUiEvent

    data class OnRemoveHeavyAppPackageClicked(val packageName: String) : SettingsUiEvent

    data class OnRollbackPackageClicked(val packageName: String) : SettingsUiEvent
}


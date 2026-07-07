package com.tony.appbooster.presentation.viewmodel.settings

/**
 * One-shot UI effects emitted by [SettingsViewModel] to the Settings screen.
 *
 * Effects represent transient actions that should be executed exactly once,
 * such as showing a snackbar or triggering navigation. Currently unused but
 * reserved for future Settings effects.
 */
sealed interface SettingsUiEffect {
    data class ShowSnackbar(val message: String) : SettingsUiEffect
}


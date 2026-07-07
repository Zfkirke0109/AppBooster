package com.tony.appbooster.presentation.viewmodel.survival

sealed interface SamsungSurvivalUiEvent {
    data object OnRefreshClicked : SamsungSurvivalUiEvent
    data object OnOpenBatterySettingsClicked : SamsungSurvivalUiEvent
}

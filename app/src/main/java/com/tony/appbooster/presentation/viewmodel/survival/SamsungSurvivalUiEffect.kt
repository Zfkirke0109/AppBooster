package com.tony.appbooster.presentation.viewmodel.survival

sealed interface SamsungSurvivalUiEffect {
    data object OpenBatterySettings : SamsungSurvivalUiEffect
    data class ShowSnackbar(val message: String) : SamsungSurvivalUiEffect
}

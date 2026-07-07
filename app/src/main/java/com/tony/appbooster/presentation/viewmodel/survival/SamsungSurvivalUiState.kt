package com.tony.appbooster.presentation.viewmodel.survival

import com.tony.appbooster.domain.model.device.DeviceGuardSnapshot

data class SamsungSurvivalUiState(
    val isRefreshing: Boolean = false,
    val snapshot: DeviceGuardSnapshot? = null,
    val errorMessage: String? = null
)

package com.tony.appbooster.presentation.viewmodel.survival

import androidx.lifecycle.viewModelScope
import com.tony.appbooster.domain.model.common.Resource
import com.tony.appbooster.domain.model.common.ResourceError
import com.tony.appbooster.domain.usecase.device.GetDeviceGuardStatusUseCase
import com.tony.appbooster.presentation.navigation.interfaces.NavigationManager
import com.tony.appbooster.presentation.viewmodel.base.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SamsungSurvivalViewModel @Inject constructor(
    navigationManager: NavigationManager,
    private val getDeviceGuardStatusUseCase: GetDeviceGuardStatusUseCase
) : BaseViewModel<SamsungSurvivalUiState, SamsungSurvivalUiEvent, SamsungSurvivalUiEffect>(
    navigationManager
) {

    override val LOG_TAG: String = "SamsungSurvivalViewModel"

    init {
        updateUiData(SamsungSurvivalUiState(isRefreshing = true))
        refresh()
    }

    override fun handleEvent(event: SamsungSurvivalUiEvent) {
        when (event) {
            SamsungSurvivalUiEvent.OnRefreshClicked -> refresh()
            SamsungSurvivalUiEvent.OnOpenBatterySettingsClicked ->
                emitEffect(SamsungSurvivalUiEffect.OpenBatterySettings)
        }
    }

    private fun refresh() {
        viewModelScope.launch(exceptionHandler) {
            updateUiData(currentUiData().copy(isRefreshing = true, errorMessage = null))
            when (val result = getDeviceGuardStatusUseCase()) {
                is Resource.Success -> {
                    updateUiData(
                        SamsungSurvivalUiState(
                            isRefreshing = false,
                            snapshot = result.data
                        )
                    )
                }
                is Resource.Error -> {
                    val message = result.data.toMessage()
                    updateUiData(
                        currentUiData().copy(
                            isRefreshing = false,
                            errorMessage = message
                        )
                    )
                    emitEffect(SamsungSurvivalUiEffect.ShowSnackbar(message))
                }
            }
        }
    }

    private fun currentUiData(): SamsungSurvivalUiState {
        return uiState.value.data ?: SamsungSurvivalUiState()
    }

    private fun ResourceError.toMessage(): String {
        return when (this) {
            is ResourceError.LogicError -> errorMessage
            is ResourceError.NetworkError -> errorMessage
            is ResourceError.DatabaseError -> message
            ResourceError.SSLError -> "Security error"
            ResourceError.UnknownError -> null
        } ?: "Unable to refresh Samsung survival status"
    }
}

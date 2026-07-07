package com.tony.appbooster.domain.usecase.device

import com.tony.appbooster.domain.repository.DeviceGuardRepository

class GetDeviceGuardStatusUseCase(
    private val repository: DeviceGuardRepository
) {
    suspend operator fun invoke() = repository.getDeviceGuardSnapshot()
}

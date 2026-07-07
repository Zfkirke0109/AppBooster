package com.tony.appbooster.domain.usecase.settings

import com.tony.appbooster.domain.repository.SettingsRepository

class ObserveHeavyAppPackagesUseCase(
    private val repository: SettingsRepository
) {
    operator fun invoke() = repository.observeHeavyAppPackages()
}

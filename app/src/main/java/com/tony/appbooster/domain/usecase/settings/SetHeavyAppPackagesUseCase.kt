package com.tony.appbooster.domain.usecase.settings

import com.tony.appbooster.domain.repository.SettingsRepository

class SetHeavyAppPackagesUseCase(
    private val repository: SettingsRepository
) {
    suspend operator fun invoke(packageNames: Set<String>) =
        repository.setHeavyAppPackages(packageNames)
}

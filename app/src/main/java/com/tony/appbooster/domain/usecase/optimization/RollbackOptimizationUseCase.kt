package com.tony.appbooster.domain.usecase.optimization

import com.tony.appbooster.domain.repository.AdbRepository

class RollbackOptimizationUseCase(
    private val repository: AdbRepository
) {
    suspend operator fun invoke(packageName: String) =
        repository.rollbackOptimization(packageName)
}

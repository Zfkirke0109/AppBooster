package com.tony.appbooster.domain.usecase.optimization

import com.tony.appbooster.domain.repository.AdbRepository

class ObserveRollbackCandidatesUseCase(
    private val repository: AdbRepository
) {
    operator fun invoke() = repository.observeRollbackCandidates()
}

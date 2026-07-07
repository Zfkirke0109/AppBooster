package com.tony.appbooster.domain.model.common

data class OptimizationRollbackCandidate(
    val packageName: String,
    val beforeFilter: String?,
    val afterFilter: String?,
    val optimizedAtMs: Long
)

package com.tony.appbooster.domain.model.telemetry

data class OptimizationStepTelemetry(
    val id: Long,
    val runId: Long,
    val stepIndex: Int,
    val totalSteps: Int,
    val packageName: String,
    val modeKey: String,
    val forceOptimize: Boolean,
    val status: String,
    val beforeFilter: String?,
    val afterFilter: String?,
    val exitCode: Int?,
    val stdout: String?,
    val stderr: String?,
    val displayCommand: String?,
    val durationMs: Long?,
    val storageBefore: StorageSnapshot?,
    val storageAfter: StorageSnapshot?,
    val verificationSource: String?,
    val createdAtMs: Long,
    val updatedAtMs: Long
)

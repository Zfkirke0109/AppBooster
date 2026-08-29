package com.tony.appbooster.domain.model.telemetry

data class StorageSnapshot(
    val totalBytes: Long,
    val availableBytes: Long,
    val reserveBytes: Long = MINIMUM_RESERVE_BYTES,
    val capturedAtMs: Long
) {
    val isBelowReserve: Boolean
        get() = availableBytes < reserveBytes

    companion object {
        const val MINIMUM_RESERVE_BYTES: Long = 5L * 1024L * 1024L * 1024L
    }
}

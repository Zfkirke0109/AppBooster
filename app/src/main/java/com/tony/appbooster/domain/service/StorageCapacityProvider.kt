package com.tony.appbooster.domain.service

import com.tony.appbooster.domain.model.telemetry.StorageSnapshot

interface StorageCapacityProvider {
    fun snapshot(): StorageSnapshot
}

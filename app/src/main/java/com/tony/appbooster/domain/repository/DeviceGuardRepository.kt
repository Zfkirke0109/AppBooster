package com.tony.appbooster.domain.repository

import com.tony.appbooster.domain.model.common.Resource
import com.tony.appbooster.domain.model.device.DeviceGuardSnapshot

interface DeviceGuardRepository {
    suspend fun getDeviceGuardSnapshot(): Resource<DeviceGuardSnapshot>
}

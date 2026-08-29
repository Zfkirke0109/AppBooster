package com.tony.appbooster.data.telemetry

import android.content.Context
import android.os.StatFs
import com.tony.appbooster.domain.model.telemetry.StorageSnapshot
import com.tony.appbooster.domain.service.StorageCapacityProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidStorageCapacityProvider @Inject constructor(
    @param:ApplicationContext private val context: Context
) : StorageCapacityProvider {
    override fun snapshot(): StorageSnapshot {
        val statFs = StatFs(context.filesDir.absolutePath)
        return StorageSnapshot(
            totalBytes = statFs.totalBytes,
            availableBytes = statFs.availableBytes,
            capturedAtMs = System.currentTimeMillis()
        )
    }
}

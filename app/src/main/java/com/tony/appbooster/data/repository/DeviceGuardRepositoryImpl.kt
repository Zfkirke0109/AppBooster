package com.tony.appbooster.data.repository

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.tony.appbooster.data.util.StandbyBucketParser
import com.tony.appbooster.data.util.ThermalStatusParser
import com.tony.appbooster.domain.client.AdbShellDataSource
import com.tony.appbooster.domain.model.common.Resource
import com.tony.appbooster.domain.model.common.ResourceError
import com.tony.appbooster.domain.model.common.ShellCommandSpec
import com.tony.appbooster.domain.model.common.requireSuccess
import com.tony.appbooster.domain.model.device.DeviceGuardSnapshot
import com.tony.appbooster.domain.repository.DeviceGuardRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlin.math.roundToInt
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceGuardRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val shellDataSource: AdbShellDataSource
) : DeviceGuardRepository {

    override suspend fun getDeviceGuardSnapshot(): Resource<DeviceGuardSnapshot> {
        return runCatching {
            val batteryPercent = readBatteryPercent()
            val thermalOutput = executeSuccessful(ShellCommandSpec.DumpsysThermalService)
            val standbyOutput = executeSuccessful(
                ShellCommandSpec.GetStandbyBucket(context.packageName)
            )

            DeviceGuardSnapshot(
                batteryPercent = batteryPercent,
                thermalStatus = ThermalStatusParser.parse(thermalOutput),
                standbyBucket = StandbyBucketParser.parse(standbyOutput)
            )
        }.fold(
            onSuccess = { Resource.Success(it) },
            onFailure = { throwable ->
                Resource.Error(
                    ResourceError.LogicError(
                        errorMessage = throwable.message ?: "Device guard check failed",
                        errorCode = "DEVICE_GUARD_CHECK_FAILED"
                    )
                )
            }
        )
    }

    private fun readBatteryPercent(): Int? {
        val batteryIntent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        ) ?: return null

        val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null

        return ((level * 100f) / scale).roundToInt().coerceIn(0, 100)
    }

    private suspend fun executeSuccessful(command: ShellCommandSpec): String {
        return shellDataSource.executeCommandDetailed(command)
            .getOrThrow()
            .requireSuccess(command.displayCommand)
            .stdout
    }
}

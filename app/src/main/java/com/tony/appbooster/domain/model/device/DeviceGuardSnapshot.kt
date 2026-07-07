package com.tony.appbooster.domain.model.device

private const val MIN_OPTIMIZATION_BATTERY_PERCENT = 35
private const val THERMAL_STATUS_SEVERE = 3

data class DeviceGuardSnapshot(
    val batteryPercent: Int?,
    val thermalStatus: ThermalStatusSnapshot,
    val standbyBucket: StandbyBucketSnapshot
) {
    val isBatteryLow: Boolean = batteryPercent != null &&
        batteryPercent < MIN_OPTIMIZATION_BATTERY_PERCENT

    val isThermalSevere: Boolean = thermalStatus.statusCode != null &&
        thermalStatus.statusCode >= THERMAL_STATUS_SEVERE

    val isStandbyRestricted: Boolean = standbyBucket.bucket == StandbyBucket.Restricted

    val blockingReasons: List<DeviceGuardBlockReason> = buildList {
        if (isBatteryLow && batteryPercent != null) {
            add(DeviceGuardBlockReason.BatteryLow(batteryPercent))
        }
        if (isThermalSevere) {
            add(DeviceGuardBlockReason.ThermalSevere(thermalStatus.label))
        }
    }

    val canOptimize: Boolean = blockingReasons.isEmpty()
}

data class ThermalStatusSnapshot(
    val statusCode: Int?,
    val label: String,
    val rawOutput: String
)

data class StandbyBucketSnapshot(
    val bucket: StandbyBucket,
    val rawValue: String
)

enum class StandbyBucket {
    Active,
    WorkingSet,
    Frequent,
    Rare,
    Restricted,
    Exempted,
    Never,
    Unknown
}

sealed interface DeviceGuardBlockReason {
    data class BatteryLow(val batteryPercent: Int) : DeviceGuardBlockReason
    data class ThermalSevere(val thermalLabel: String) : DeviceGuardBlockReason
}

fun DeviceGuardSnapshot.blockingSummary(): String {
    return blockingReasons.joinToString(separator = "; ") { reason ->
        when (reason) {
            is DeviceGuardBlockReason.BatteryLow ->
                "Battery is ${reason.batteryPercent}%, below the 35% guard"
            is DeviceGuardBlockReason.ThermalSevere ->
                "Thermal status is ${reason.thermalLabel}"
        }
    }.ifBlank {
        "Device guard blocked optimization"
    }
}

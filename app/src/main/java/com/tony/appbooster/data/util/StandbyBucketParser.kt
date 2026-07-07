package com.tony.appbooster.data.util

import com.tony.appbooster.domain.model.device.StandbyBucket
import com.tony.appbooster.domain.model.device.StandbyBucketSnapshot

object StandbyBucketParser {

    fun parse(output: String): StandbyBucketSnapshot {
        val normalized = output.trim().lowercase()
        val bucket = when {
            normalized.contains("restricted") || normalized == "45" -> StandbyBucket.Restricted
            normalized.contains("working_set") || normalized.contains("working set") || normalized == "20" ->
                StandbyBucket.WorkingSet
            normalized.contains("frequent") || normalized == "30" -> StandbyBucket.Frequent
            normalized.contains("exempted") || normalized == "5" -> StandbyBucket.Exempted
            normalized.contains("active") || normalized == "10" -> StandbyBucket.Active
            normalized.contains("rare") || normalized == "40" -> StandbyBucket.Rare
            normalized.contains("never") || normalized == "50" -> StandbyBucket.Never
            else -> StandbyBucket.Unknown
        }

        return StandbyBucketSnapshot(
            bucket = bucket,
            rawValue = output.trim().ifBlank { "unknown" }
        )
    }
}

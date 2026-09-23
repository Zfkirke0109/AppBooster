package com.tony.appbooster.data.performance

import com.tony.appbooster.domain.model.performance.LaunchTiming

/** Strict parser for successful process-cold `am start -S -W` output. */
internal object StartupOutputParser {
    fun parse(output: String, packageName: String): LaunchTiming {
        val lines = output.lineSequence().map(String::trim).toList()
        fun field(key: String) = lines.filter { it.startsWith("$key:") }.singleOrNull()?.substringAfter(':')?.trim()
        require(field("Status") == "ok" && field("LaunchState") == "COLD" && "Complete" in lines &&
            !output.contains("truncated", ignoreCase = true) && !output.contains("Error:", ignoreCase = true)) {
            "Android did not report a complete cold launch. Sample excluded."
        }
        val activity = field("Activity").orEmpty()
        require(activity.startsWith("$packageName/")) { "Launch reached a different app. Sample excluded." }
        val total = field("TotalTime")?.toLongOrNull()
        require(total != null && total > 0) { "Android did not provide a valid TotalTime. Sample excluded." }
        return LaunchTiming(total, field("ThisTime")?.toLongOrNull()?.takeIf { it >= 0 },
            field("WaitTime")?.toLongOrNull()?.takeIf { it >= 0 }, activity)
    }
}

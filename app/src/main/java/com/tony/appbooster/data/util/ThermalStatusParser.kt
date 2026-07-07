package com.tony.appbooster.data.util

import com.tony.appbooster.domain.model.device.ThermalStatusSnapshot

object ThermalStatusParser {

    private val statusPatterns = listOf(
        Regex("""(?i)\bmStatus\s*=\s*(\d+)"""),
        Regex("""(?i)\bthermal\s+status\s*[:=]\s*(\d+)"""),
        Regex("""(?i)\bstatus\s*[:=]\s*(\d+)""")
    )

    fun parse(output: String): ThermalStatusSnapshot {
        val statusCode = statusPatterns
            .asSequence()
            .mapNotNull { pattern -> pattern.find(output)?.groupValues?.getOrNull(1)?.toIntOrNull() }
            .firstOrNull()

        return ThermalStatusSnapshot(
            statusCode = statusCode,
            label = labelFor(statusCode),
            rawOutput = output
        )
    }

    private fun labelFor(statusCode: Int?): String {
        return when (statusCode) {
            0 -> "none"
            1 -> "light"
            2 -> "moderate"
            3 -> "severe"
            4 -> "critical"
            5 -> "emergency"
            6 -> "shutdown"
            else -> "unknown"
        }
    }
}

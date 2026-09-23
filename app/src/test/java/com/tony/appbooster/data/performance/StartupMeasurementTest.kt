package com.tony.appbooster.data.performance

import com.tony.appbooster.domain.model.performance.LaunchStatistics
import org.junit.Assert.*
import org.junit.Test

class StartupMeasurementTest {
    private val valid = "Status: ok\nLaunchState: COLD\nActivity: com.example.app/.MainActivity\nThisTime: 390\nTotalTime: 420\nWaitTime: 430\nComplete"

    @Test fun `uses Android total launch time rather than shell wait time`() {
        val result = StartupOutputParser.parse(valid, "com.example.app")
        assertEquals(420L, result.totalTimeMs)
        assertEquals(430L, result.waitTimeMs)
    }
    @Test fun `timeout warm wrong-package zero and truncated captures are rejected`() {
        listOf(valid.replace("Status: ok", "Status: timeout"), valid.replace("COLD", "WARM"),
            valid.replace("com.example.app/", "com.other.app/"), valid.replace("TotalTime: 420", "TotalTime: 0"),
            valid.replace("Complete", "[output truncated by ShellService]"), valid.replace("TotalTime: 420", "TotalTime: unknown")
        ).forEach { assertThrows(IllegalArgumentException::class.java) { StartupOutputParser.parse(it, "com.example.app") } }
    }
    @Test fun `median resists single slow sample and change preserves regression sign`() {
        val before = LaunchStatistics.from(listOf(500, 520, 480, 510, 2000).map(Int::toLong))
        val after = LaunchStatistics.from(listOf(600, 590, 610, 580, 620).map(Int::toLong))
        assertEquals(510.0, before.medianMs, 0.001)
        assertEquals(480L, before.minMs)
        assertEquals(2000L, before.maxMs)
        assertEquals(-17.647, before.improvementPercent(after), 0.001)
    }
    @Test fun `incomplete phases cannot become reported statistics`() {
        assertThrows(IllegalArgumentException::class.java) { LaunchStatistics.from(listOf(1, 2, 3, 4)) }
        assertThrows(IllegalArgumentException::class.java) { LaunchStatistics.from(listOf(1, 2, 3, 4, 0)) }
    }
}

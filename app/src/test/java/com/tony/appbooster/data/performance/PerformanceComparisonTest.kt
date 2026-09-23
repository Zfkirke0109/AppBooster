package com.tony.appbooster.data.performance

import com.tony.appbooster.domain.model.performance.*
import org.junit.Assert.*
import org.junit.Test

class PerformanceComparisonTest {
    private val app = MeasurementApp("com.example.app", "Example", "com.example.app/.MainActivity", 10, 100)
    private val conditions = MeasurementConditions(300, 80, 0, 0, false, true)
    private fun phase() = MeasurementPhase(app, "firmware", "art", 100, 200, 100,
        (1..5).map { LaunchSample(LaunchTiming(100, 90, 120, app.component), 150, conditions, conditions, "raw") }, "verify", bootCount = 7)
    @Test fun `version change prevents a paired result`() {
        val before = phase()
        assertTrue(comparisonProblems(before, before.copy(app = app.copy(versionCode = 11))).isNotEmpty())
        assertTrue(comparisonProblems(before, before.copy(app = app.copy(lastUpdateTime = 101))).isNotEmpty())
        assertTrue(comparisonProblems(before, before.copy(buildFingerprint = "changed")).isNotEmpty())
    }
    @Test fun `thermal and power state changes cannot masquerade as compile improvements`() {
        val before = phase()
        val after = before.copy(samples = before.samples.map { it.copy(after = conditions.copy(powerSave = true, batteryTemperatureDeciC = 340)) })
        assertTrue(comparisonProblems(before, after).size >= 2)
    }
    @Test fun `complete comparable phases permit descriptive statistics`() {
        assertTrue(comparisonProblems(phase(), phase()).isEmpty())
    }
    @Test fun `missing matching ART identity does not prove runtime remained unchanged`() {
        for (unknown in listOf("unavailable", "")) {
            val before = phase().copy(artVersion = unknown)
            assertTrue(comparisonProblems(before, before).isNotEmpty())
        }
    }
    @Test fun `unknown thermal temperature or charging evidence makes comparison inconclusive`() {
        for (unknown in listOf(conditions.copy(thermalStatus = null),
            conditions.copy(batteryTemperatureDeciC = null), conditions.copy(plugged = null))) {
            val before = phase().let { phase -> phase.copy(samples = phase.samples.map {
                it.copy(before = unknown, after = unknown)
            }) }
            assertTrue(comparisonProblems(before, before).isNotEmpty())
        }
    }
    @Test fun `reboot is rejected even when later uptime has exceeded baseline uptime`() {
        val before = phase()
        assertTrue(comparisonProblems(before, before.copy(bootCount = 8, elapsedRealtimeMs = 1_000)).isNotEmpty())
    }
    @Test fun `missing boot identity cannot establish comparable phases`() {
        val before = phase().copy(bootCount = null)
        assertTrue(comparisonProblems(before, before).isNotEmpty())
    }
}

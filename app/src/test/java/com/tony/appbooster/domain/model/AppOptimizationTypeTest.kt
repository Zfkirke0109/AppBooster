package com.tony.appbooster.domain.model

import com.tony.appbooster.domain.model.settings.AppOptimizationType
import com.tony.appbooster.domain.model.settings.OptimizationTargetScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppOptimizationTypeTest {

    @Test
    fun `speed profile maps to speed-profile and all eligible packages`() {
        val mode = AppOptimizationType.SPEED_PROFILE

        assertEquals("speed-profile", mode.requestedCompileMode)
        assertEquals(OptimizationTargetScope.AllEligible, mode.targetScope)
        assertFalse(mode.useFullDexoptScope)
    }

    @Test
    fun `full DEXtoOAT speed maps to speed full scope and all eligible packages`() {
        val mode = AppOptimizationType.FULL_DEX2OAT_SPEED

        assertEquals("speed", mode.requestedCompileMode)
        assertEquals(OptimizationTargetScope.AllEligible, mode.targetScope)
        assertTrue(mode.useFullDexoptScope)
    }

    @Test
    fun `advanced full compile maps to everything and requires runtime support`() {
        val mode = AppOptimizationType.ADVANCED_FULL_COMPILE

        assertEquals("everything", mode.requestedCompileMode)
        assertEquals(OptimizationTargetScope.AllEligible, mode.targetScope)
        assertTrue(mode.useFullDexoptScope)
        assertTrue(mode.requiresRuntimeModeSupportCheck)
    }

    @Test
    fun `heavy apps speed maps to speed full scope and selected packages`() {
        val mode = AppOptimizationType.HEAVY_APPS_SPEED

        assertEquals("speed", mode.requestedCompileMode)
        assertEquals(OptimizationTargetScope.SelectedHeavyApps, mode.targetScope)
        assertTrue(mode.useFullDexoptScope)
    }
}

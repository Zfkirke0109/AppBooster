package com.tony.appbooster.domain.model

import com.tony.appbooster.domain.model.settings.AppOptimizationType
import com.tony.appbooster.domain.model.settings.OptimizationTargetScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppOptimizationTypeTest {

    @Test
    fun `speed profile maps to speed-profile all eligible packages and normal scope`() {
        val mode = AppOptimizationType.SPEED_PROFILE

        assertEquals("speed-profile", mode.requestedCompileMode)
        assertEquals(OptimizationTargetScope.AllEligible, mode.targetScope)
        assertFalse(mode.useFullDexoptScope)
    }

    @Test
    fun `full DEXtoOAT speed maps to speed all eligible packages and normal scope`() {
        val mode = AppOptimizationType.FULL_DEX2OAT_SPEED

        assertEquals("speed", mode.requestedCompileMode)
        assertEquals(OptimizationTargetScope.AllEligible, mode.targetScope)
        assertFalse(mode.useFullDexoptScope)
    }

    @Test
    fun `full compile dexopt all maps to speed all eligible packages and full dexopt scope`() {
        val mode = AppOptimizationType.ADVANCED_FULL_COMPILE

        assertEquals("Full Compile / DEXopt All", mode.displayName)
        assertEquals("speed", mode.requestedCompileMode)
        assertEquals(OptimizationTargetScope.AllEligible, mode.targetScope)
        assertTrue(mode.useFullDexoptScope)
        assertTrue(mode.requiresRuntimeModeSupportCheck)
    }

    @Test
    fun `heavy apps speed maps to speed selected packages and normal scope`() {
        val mode = AppOptimizationType.HEAVY_APPS_SPEED

        assertEquals("speed", mode.requestedCompileMode)
        assertEquals(OptimizationTargetScope.SelectedHeavyApps, mode.targetScope)
        assertFalse(mode.useFullDexoptScope)
    }

    @Test
    fun `stored enum keys resolve to their own mode`() {
        AppOptimizationType.entries.forEach { mode ->
            assertEquals(mode, AppOptimizationType.fromStoredValue(mode.value))
            assertEquals(mode, AppOptimizationType.fromStoredValue(mode.name))
        }
    }

    @Test
    fun `legacy stored values migrate to the owning mode`() {
        assertEquals(
            AppOptimizationType.SPEED_PROFILE,
            AppOptimizationType.fromStoredValue("speed-profile")
        )
        assertEquals(
            AppOptimizationType.FULL_DEX2OAT_SPEED,
            AppOptimizationType.fromStoredValue("speed")
        )
        assertNull(AppOptimizationType.fromStoredValue("everything"))
        assertEquals(
            AppOptimizationType.HEAVY_APPS_SPEED,
            AppOptimizationType.fromStoredValue("full_optimization")
        )
    }

    @Test
    fun `unknown stored values resolve to null so callers fail visibly`() {
        assertNull(AppOptimizationType.fromStoredValue(null))
        assertNull(AppOptimizationType.fromStoredValue(""))
        assertNull(AppOptimizationType.fromStoredValue("not_a_mode"))
    }
}

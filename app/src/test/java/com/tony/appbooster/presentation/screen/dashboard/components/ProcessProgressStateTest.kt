package com.tony.appbooster.presentation.screen.dashboard.components

import com.tony.appbooster.domain.model.common.OptimizationProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProcessProgressStateTest {
    @Test
    fun `elapsed time uses package monotonic start and survives progress updates`() {
        val progress = OptimizationProgress(
            runId = 42,
            currentAppPackage = "com.example.heavy",
            currentPackageStartedAtElapsedMs = 12_000L
        )
        val state = optimizing(progress)
        assertEquals(61L, state.currentPackageElapsedSeconds(73_999L))
        assertEquals(75L, optimizing(progress.copy(processedCount = 5)).currentPackageElapsedSeconds(87_000L))
        assertEquals(0L, optimizing(progress.copy(
            currentAppPackage = "com.example.next",
            currentPackageStartedAtElapsedMs = 87_000L
        )).currentPackageElapsedSeconds(87_999L))
    }

    @Test
    fun `preparing without current package uses analysis copy and no package timer`() {
        val state = optimizing(OptimizationProgress(currentPackageStartedAtElapsedMs = 12_000L))
        assertEquals("Preparing", state.title)
        assertEquals("Analyzing installed apps", state.subtitle)
        assertNull(state.currentPackageElapsedSeconds(100_000L))
    }

    @Test
    fun `missing or future package start never invents elapsed duration`() {
        assertNull(optimizing(OptimizationProgress(currentAppPackage = "com.example.app"))
            .currentPackageElapsedSeconds(100_000L))
        assertEquals(0L, optimizing(OptimizationProgress(
            currentAppPackage = "com.example.app",
            currentPackageStartedAtElapsedMs = 101_000L
        )).currentPackageElapsedSeconds(100_000L))
    }

    private fun optimizing(progress: OptimizationProgress) = ProcessProgressState.fromOptimizationProgress(
        progress = progress,
        titleText = "Optimizing",
        preparingTitleText = "Preparing",
        preparingSubtitleText = "Analyzing installed apps"
    )
}

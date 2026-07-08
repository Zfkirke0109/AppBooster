package com.tony.appbooster.presentation.worker

import com.tony.appbooster.domain.model.common.OptimizationProgress
import com.tony.appbooster.domain.model.common.OptimizationResult
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Unit tests for [toNotificationProgressKey].
 */
class OptimizationWorkerNotificationKeyTest {

    @Test
    fun `given same counters but terminal completion when key generated then key changes`() {
        val inFlight = OptimizationProgress(
            isRunning = true,
            result = OptimizationResult.None,
            currentAppPackage = "com.example.app",
            progress = 1f,
            processedCount = 10,
            totalCount = 10
        )
        val completed = inFlight.copy(
            isRunning = false,
            result = OptimizationResult.Completed
        )

        assertNotEquals(inFlight.toNotificationProgressKey(), completed.toNotificationProgressKey())
    }
}

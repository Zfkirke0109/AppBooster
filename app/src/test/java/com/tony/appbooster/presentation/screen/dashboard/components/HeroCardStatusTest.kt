package com.tony.appbooster.presentation.screen.dashboard.components

import com.tony.appbooster.domain.model.common.OptimizationProgress
import com.tony.appbooster.domain.model.common.OptimizationResult
import com.tony.appbooster.domain.model.settings.AppOptimizationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeroCardStatusTest {
    @Test
    fun `all terminal states preserve explicit populations and actual unprocessed targets`() {
        val results = listOf(
            OptimizationResult.Completed,
            OptimizationResult.CompletedWithIssues,
            OptimizationResult.Canceled,
            OptimizationResult.Failed,
            OptimizationResult.Paused("Battery is low")
        )
        results.forEach { result ->
            // 20 compile targets: 12 processed, 8 not processed. Seven matches,
            // three no-profile and two not-applicable skips preceded the queue.
            val status = requireNotNull(HeroCardStatus.fromProgress(
                OptimizationProgress(
                    result = result,
                    totalCount = 20,
                    processedCount = 12,
                    skippedCount = 12,
                    optimizedSucceededCount = 4,
                    alreadyOptimizedCount = 7,
                    skippedNoProfileCount = 3,
                    failedOrRefusedCount = 2,
                    osAdjustedFilterCount = 3,
                    skippedNotApplicableCount = 4,
                    verificationUnavailableCount = 1
                ),
                AppOptimizationType.SPEED_PROFILE
            ))
            assertEquals(result.toString(), 11, status.counts.matchingCount)
            assertEquals(8, status.counts.unprocessedCount)
            assertEquals(3, status.counts.noProfileCount)
            assertEquals(2, status.counts.failedCount)
            assertEquals(3, status.counts.osAdjustedCount)
            assertEquals(4, status.counts.skippedNotApplicableCount)
            assertEquals(1, status.counts.verificationUnavailableCount)
        }
    }

    @Test
    fun `Samsung fixture remains 700 exact matches with distinct adjusted and not applicable counts`() {
        val status = requireNotNull(HeroCardStatus.fromProgress(
            OptimizationProgress(
                result = OptimizationResult.CompletedWithIssues,
                processedCount = 86,
                totalCount = 86,
                skippedCount = 670,
                alreadyOptimizedCount = 670,
                optimizedSucceededCount = 30,
                unverifiedCount = 37,
                osAdjustedFilterCount = 37,
                skippedNotApplicableCount = 19
            ),
            AppOptimizationType.ADVANCED_FULL_COMPILE
        ))
        assertEquals(700, status.counts.matchingCount)
        assertEquals(37, status.counts.osAdjustedCount)
        assertEquals(19, status.counts.skippedNotApplicableCount)
        assertEquals(0, status.counts.unprocessedCount)
        assertTrue(status.isAndroidAdjustedCompletion)
    }

    @Test
    fun `failure or unavailable verification keeps adjusted completion alarming`() {
        listOf(
            OptimizationProgress(failedOrRefusedCount = 1),
            OptimizationProgress(verificationUnavailableCount = 1)
        ).forEach { progress ->
            val status = requireNotNull(HeroCardStatus.fromProgress(
                progress.copy(
                    result = OptimizationResult.CompletedWithIssues,
                    osAdjustedFilterCount = 5
                ),
                AppOptimizationType.ADVANCED_FULL_COMPILE
            ))
            assertFalse(status.isAndroidAdjustedCompletion)
        }
    }

    @Test
    fun `not applicable and no profile alone cannot claim all apps optimized`() {
        val status = HeroCardStatus.fromProgress(
            OptimizationProgress(
                result = OptimizationResult.Completed,
                skippedCount = 5,
                skippedNotApplicableCount = 3,
                skippedNoProfileCount = 2
            ),
            AppOptimizationType.SPEED_PROFILE
        )
        assertTrue(status is HeroCardStatus.Completed)
        assertEquals(0, status?.counts?.matchingCount)
    }

    @Test
    fun `remaining count never becomes negative for restored terminal progress`() {
        val status = requireNotNull(HeroCardStatus.fromProgress(
            OptimizationProgress(result = OptimizationResult.Canceled, totalCount = 3, processedCount = 4),
            AppOptimizationType.SPEED_PROFILE
        ))
        assertEquals(0, status.counts.unprocessedCount)
    }
}

package com.tony.appbooster.presentation

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tony.appbooster.domain.model.common.OptimizationProgress
import com.tony.appbooster.domain.model.common.OptimizationResult
import com.tony.appbooster.domain.model.settings.AppOptimizationType
import com.tony.appbooster.presentation.screen.dashboard.components.DashboardHeroCard
import com.tony.appbooster.presentation.viewmodel.main.MainUiModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Guards the result shown on the Samsung run against subtracting unrelated outcomes. */
@RunWith(AndroidJUnit4::class)
class DashboardResultCountsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun androidAdjustedAndNotApplicablePackagesDoNotReduceAlreadyMatchingCount() {
        // September 19 device run: 670 already matching + 30 newly verified = 700.
        // The remaining 37 adjusted and 19 not-applicable packages are separate.
        val model = MainUiModel(
            optimizationMode = AppOptimizationType.ADVANCED_FULL_COMPILE,
            optimizationProgress = OptimizationProgress(
                result = OptimizationResult.CompletedWithIssues,
                progress = 1f,
                processedCount = 86,
                totalCount = 86,
                skippedCount = 670,
                alreadyOptimizedCount = 670,
                optimizedSucceededCount = 30,
                unverifiedCount = 37,
                osAdjustedFilterCount = 37,
                skippedNotApplicableCount = 19
            )
        )
        compose.setContent {
            MaterialTheme {
                DashboardHeroCard(
                    model = model,
                    onStartOptimization = {},
                    onForceOptimize = {},
                    onStopOptimization = {},
                    onDismissResult = {},
                    onAnalyze = {},
                    onStopAnalysis = {}
                )
            }
        }
        compose.onNodeWithText("700").assertIsDisplayed()
        compose.onNodeWithText("37").assertIsDisplayed()
        compose.onNodeWithText("19").assertIsDisplayed()
    }
}

package com.tony.appbooster.presentation

import android.os.SystemClock
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tony.appbooster.domain.model.common.OptimizationProgress
import com.tony.appbooster.domain.model.common.OptimizationAnalysis
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
        compose.onNodeWithText("Completed — Android adjusted").assertIsDisplayed()
        compose.onNodeWithText("Finished with issues").assertDoesNotExist()
    }

    @Test
    fun completedDoesNotCountNotApplicablePackagesAsExactMatches() {
        showProgress(OptimizationProgress(
            result = OptimizationResult.Completed,
            processedCount = 10,
            totalCount = 10,
            optimizedSucceededCount = 5,
            alreadyOptimizedCount = 10,
            skippedNoProfileCount = 3,
            skippedNotApplicableCount = 5,
            skippedCount = 18
        ))
        compose.onNodeWithText("15").assertIsDisplayed()
        compose.onNodeWithText("5").assertIsDisplayed()
        compose.onNodeWithText("3").assertIsDisplayed()
        compose.onNodeWithText("23").assertDoesNotExist()
    }

    @Test
    fun cancellationPreservesEveryOutcomeAndOnlyLabelsUnprocessedTargetsAsRemaining() {
        showProgress(OptimizationProgress(
            result = OptimizationResult.Canceled,
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
        ))
        compose.onNodeWithText("11").assertIsDisplayed()
        compose.onNodeWithText("8").assertIsDisplayed()
        compose.onNodeWithText("Not processed").assertIsDisplayed()
        compose.onNodeWithText("Failed / Refused").assertIsDisplayed()
        compose.onNodeWithText("Post-run ART verification unavailable").assertIsDisplayed()
        compose.onNodeWithText("Skipped because ART reported the package as not applicable").assertIsDisplayed()
    }

    @Test
    fun failureIsStillReportedAlongsideAndroidAdjustedResults() {
        showProgress(OptimizationProgress(
            result = OptimizationResult.CompletedWithIssues,
            totalCount = 2,
            processedCount = 2,
            failedOrRefusedCount = 1,
            osAdjustedFilterCount = 1
        ))
        compose.onNodeWithText("Finished with issues").assertIsDisplayed()
        compose.onNodeWithText("Completed — Android adjusted").assertDoesNotExist()
    }

    @Test
    fun longRunningPackageShowsElapsedFeedbackWithoutAnotherProgressEvent() {
        showProgress(OptimizationProgress(
            runId = 42L,
            isRunning = true,
            totalCount = 1,
            currentAppPackage = "com.example.heavy",
            currentPackageStartedAtElapsedMs = SystemClock.elapsedRealtime() - 20_000L
        ))
        compose.onNodeWithText("com.example.heavy").assertIsDisplayed()
        compose.onNodeWithText("Working on this app", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Waiting for Android to finish this app.", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun runPreparationExplainsAnalysisWithoutShowingAStalePackageTimer() {
        showProgress(OptimizationProgress(
            isRunning = true,
            currentPackageStartedAtElapsedMs = SystemClock.elapsedRealtime() - 20_000L
        ))
        compose.onNodeWithText("Preparing optimization").assertIsDisplayed()
        compose.onNodeWithText("Analyzing installed apps and preparing the compile plan").assertIsDisplayed()
        compose.onNodeWithText("Working on this app", substring = true).assertDoesNotExist()
    }

    private fun showProgress(progress: OptimizationProgress) {
        // An old analysis snapshot must never supply counters for the current result.
        val model = MainUiModel(
            optimizationProgress = progress,
            optimizationAnalysis = OptimizationAnalysis(appsWithNoProfile = 99)
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
    }
}

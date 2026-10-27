package com.tony.appbooster.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tony.appbooster.domain.model.common.OptimizationProgress
import com.tony.appbooster.domain.model.common.OptimizationResult
import com.tony.appbooster.domain.model.settings.AppOptimizationType
import com.tony.appbooster.presentation.screen.dashboard.DashboardTabletLayout
import com.tony.appbooster.presentation.viewmodel.main.MainUiModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Keeps result actions usable in the S26 landscape pane and with larger text. */
@RunWith(AndroidJUnit4::class)
class DashboardAdaptiveLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun landscapeResultActionsRemainReachableAtS26FontScale() {
        assertResultActionsRemainReachable(fontScale = 1.08f)
    }

    @Test
    fun landscapeResultActionsRemainReachableWithLargeText() {
        assertResultActionsRemainReachable(fontScale = 1.5f)
    }

    private fun assertResultActionsRemainReachable(fontScale: Float) {
        val actions = mutableListOf<String>()
        val model = MainUiModel(
            optimizationMode = AppOptimizationType.ADVANCED_FULL_COMPILE,
            optimizationProgress = OptimizationProgress(
                result = OptimizationResult.Canceled,
                totalCount = 438,
                processedCount = 421,
                optimizedSucceededCount = 400,
                alreadyOptimizedCount = 161,
                failedOrRefusedCount = 1,
                osAdjustedFilterCount = 15,
                skippedNotApplicableCount = 4,
                verificationUnavailableCount = 1
            )
        )
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                MaterialTheme {
                    // Logical landscape bounds; density 1 keeps the test inside the emulator display.
                    Box(Modifier.requiredSize(832.dp, 320.dp)) {
                        DashboardTabletLayout(
                            model = model,
                            onStartOptimization = { actions += "run" },
                            onForceOptimize = { actions += "force" },
                            onStopOptimization = {},
                            onDismissResult = {},
                            onAnalyze = {},
                            onStopAnalysis = {}
                        )
                    }
                }
            }
        }

        compose.onNodeWithText("Run again").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Re-optimize all").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("run", "force"), actions) }
    }
}

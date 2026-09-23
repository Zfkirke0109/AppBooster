package com.tony.appbooster.presentation

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tony.appbooster.domain.model.common.LogEntryType
import com.tony.appbooster.domain.model.common.OptimizationLogEntry
import com.tony.appbooster.presentation.screen.dashboard.components.OptimizationActivityFeed
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Ensures scan failures expose useful diagnostic details in the activity feed. */
@RunWith(AndroidJUnit4::class)
class OptimizationActivityFeedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun scanFailureDetailIsVisible() {
        val detail = "PatternSyntaxException: invalid pattern"
        compose.setContent {
            MaterialTheme {
                OptimizationActivityFeed(listOf(OptimizationLogEntry(
                    type = LogEntryType.ERROR,
                    message = "Analysis failed",
                    detail = detail
                )))
            }
        }
        compose.onNodeWithText("Analysis failed").assertIsDisplayed()
        compose.onNodeWithText(detail).assertIsDisplayed()
    }
}

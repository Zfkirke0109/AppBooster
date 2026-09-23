package com.tony.appbooster.presentation.screen.dashboard.components

/**
 * Disjoint populations displayed for every completed or interrupted optimization run.
 *
 * @property succeededCount Packages verified at the requested filter after compilation.
 * @property alreadyOptimizedCount Packages that already matched the requested filter.
 * @property noProfileCount Packages intentionally skipped because they lack a runtime profile.
 * @property failedCount Packages whose command failed or was refused.
 * @property osAdjustedCount Packages for which Android selected a different compiler filter.
 * @property skippedNotApplicableCount Packages ART reported as not applicable.
 * @property verificationUnavailableCount Packages without post-command verification evidence.
 * @property unprocessedCount Compile targets that have not reached an outcome.
 */
data class HeroResultCounts(
    val succeededCount: Int = 0,
    val alreadyOptimizedCount: Int = 0,
    val noProfileCount: Int = 0,
    val failedCount: Int = 0,
    val osAdjustedCount: Int = 0,
    val skippedNotApplicableCount: Int = 0,
    val verificationUnavailableCount: Int = 0,
    val unprocessedCount: Int = 0
) {
    /** Packages with positive evidence of the exact requested compiler filter. */
    val matchingCount: Int get() = succeededCount + alreadyOptimizedCount

    /** All considered packages, including pre-compile skips and remaining compile targets. */
    val totalCount: Int get() = matchingCount + noProfileCount + failedCount + osAdjustedCount +
        skippedNotApplicableCount + verificationUnavailableCount + unprocessedCount
}

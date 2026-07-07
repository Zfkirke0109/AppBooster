package com.tony.appbooster.domain.model.common

/**
 * Sealed class hierarchy for analysis and optimization states.
 * 
 * Explicitly sealed to prevent R8 obfuscation from causing ClassCastExceptions
 * when composables collect and render these state transitions.
 * 
 * Each subclass is a complete, immutable domain entity. The UI layer must
 * use exhaustive `when` to handle all cases and avoid type mismatches.
 */
sealed class AnalysisState {
    /**
     * No analysis has been performed or UI should show initial/idle state.
     */
    data object Idle : AnalysisState()

    /**
     * Analysis is currently in progress. The UI should show a loading indicator.
     *
     * @property currentPackage Name of the package currently being analyzed.
     * @property scannedCount Number of packages analyzed so far.
     * @property totalCount Total packages to analyze.
     */
    data class Loading(
        val currentPackage: String = "",
        val scannedCount: Int = 0,
        val totalCount: Int = 0
    ) : AnalysisState()

    /**
     * Analysis completed successfully with results.
     *
     * @property analysis Complete analysis data including package lists and statistics.
     */
    data class Success(val analysis: OptimizationAnalysis) : AnalysisState()

    /**
     * Analysis failed with an error. The UI should display the error message to the user.
     *
     * @property error Details about why the analysis failed.
     * @property message Human-readable error message for display.
     */
    data class Error(
        val error: ResourceError,
        val message: String
    ) : AnalysisState()
}

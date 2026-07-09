package com.tony.appbooster.domain.model.settings

/**
 * Describes the package-manager compiler filter separately from the target package scope.
 *
 * The raw ART compiler filter is not enough to identify the user-selected mode:
 * both all-app Full DEXtoOAT Speed and selected Gaming / Heavy Apps use `speed`,
 * but they intentionally target different package sets.
 */
enum class AppOptimizationType(
    val value: String,
    val displayName: String,
    val requestedCompileMode: String,
    val targetScope: OptimizationTargetScope,
    val useFullDexoptScope: Boolean = false,
    val requiresRuntimeModeSupportCheck: Boolean = false
) {
    SPEED_PROFILE(
        value = "SPEED_PROFILE",
        displayName = "Speed Profile",
        requestedCompileMode = "speed-profile",
        targetScope = OptimizationTargetScope.AllEligible
    ),

    FULL_DEX2OAT_SPEED(
        value = "FULL_DEX2OAT_SPEED",
        displayName = "Full DEXtoOAT Speed",
        requestedCompileMode = "speed",
        targetScope = OptimizationTargetScope.AllEligible,
        useFullDexoptScope = true
    ),

    ADVANCED_FULL_COMPILE(
        value = "ADVANCED_FULL_COMPILE",
        displayName = "Advanced Full Compile",
        requestedCompileMode = "everything",
        targetScope = OptimizationTargetScope.AllEligible,
        useFullDexoptScope = true,
        requiresRuntimeModeSupportCheck = true
    ),

    HEAVY_APPS_SPEED(
        value = "HEAVY_APPS_SPEED",
        displayName = "Gaming / Heavy Apps",
        requestedCompileMode = "speed",
        targetScope = OptimizationTargetScope.SelectedHeavyApps,
        useFullDexoptScope = true
    );

    companion object {
        fun fromStoredValue(value: String?): AppOptimizationType? {
            val normalized = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null

            return entries.firstOrNull { type ->
                type.name.equals(normalized, ignoreCase = true) ||
                    type.value.equals(normalized, ignoreCase = true)
            } ?: when (normalized.lowercase()) {
                "speed-profile" -> SPEED_PROFILE
                "speed" -> FULL_DEX2OAT_SPEED
                // Preserve the old two-mode selector semantics for existing DataStore values.
                "full_optimization" -> HEAVY_APPS_SPEED
                else -> null
            }
        }
    }
}

sealed interface OptimizationTargetScope {
    data object AllEligible : OptimizationTargetScope
    data object SelectedHeavyApps : OptimizationTargetScope
}

data class CompileModeSupport(
    val requestedMode: String,
    val isSupported: Boolean,
    val reason: String? = null
)

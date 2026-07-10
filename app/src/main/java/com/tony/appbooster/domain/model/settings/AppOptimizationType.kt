package com.tony.appbooster.domain.model.settings

/**
 * Describes the package-manager compiler filter separately from the target package scope
 * and the dexopt compile scope.
 *
 * The raw ART compiler filter is not enough to identify the user-selected mode:
 * Full DEXtoOAT Speed, Full Compile / DEXopt All, and Gaming / Heavy Apps all use
 * `speed`, but they intentionally differ in which packages they target and whether
 * the compile covers the full dexopt scope (`--full`).
 *
 * On One UI 8.5 / Android 16 (Galaxy S23 Ultra) `cmd package help` advertises only
 * the `speed`, `speed-profile`, and `verify` compiler filters plus `--full`
 * ("Dexopt all above. (Recommended)"). The `everything` filter is not advertised,
 * so the real full-compile command on that build is
 * `cmd package compile -m speed -f --full <package>`. `everything` remains known to
 * parsers/validators only as historical/diagnostic handling for other Android builds.
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
        targetScope = OptimizationTargetScope.AllEligible
    ),

    ADVANCED_FULL_COMPILE(
        value = "ADVANCED_FULL_COMPILE",
        displayName = "Full Compile / DEXopt All",
        requestedCompileMode = "speed",
        targetScope = OptimizationTargetScope.AllEligible,
        useFullDexoptScope = true,
        requiresRuntimeModeSupportCheck = true
    ),

    HEAVY_APPS_SPEED(
        value = "HEAVY_APPS_SPEED",
        displayName = "Gaming / Heavy Apps",
        requestedCompileMode = "speed",
        targetScope = OptimizationTargetScope.SelectedHeavyApps
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
                // Historical raw-filter value from builds that advertised `everything`;
                // resolve it to the mode that owns the full-compile intent.
                "everything" -> ADVANCED_FULL_COMPILE
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

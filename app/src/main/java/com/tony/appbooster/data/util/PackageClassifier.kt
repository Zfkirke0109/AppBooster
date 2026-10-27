package com.tony.appbooster.data.util

/**
 * Classifies packages using `dumpsys package <pkg>` output.
 *
 * Business purpose:
 * - Avoids misclassifying overlay/RRO/resource-only packages as "needs optimization".
 * - Keeps real system apps eligible for compilation.
 */
internal object PackageClassifier {

    /**
     * Determines whether the package is very likely an overlay / RRO style artifact.
     *
     * Strong signals:
     * - codePath/resourcePath pointing to /overlay/
     * - presence of overlayTarget= in the package dump
     * - bounded RRO/overlay name components when executable-code evidence is absent
     *
     * @param packageName Package name.
     * @param dumpsysPackageOutput Raw output of `dumpsys package <packageName>`.
     * @return True if the package is overlay-like.
     */
    fun isOverlayLike(packageName: String, dumpsysPackageOutput: String?): Boolean {
        // An executable APK stays eligible even if its name resembles a resource overlay.
        if (dumpsysPackageOutput != null && DexoptStatusParser.parsePackageHasCode(dumpsysPackageOutput) == true) {
            return false
        }
        val lowerPkg = packageName.lowercase()

        // Name-based signal (fallback).
        val nameSuggestsOverlay = lowerPkg.split('.', '_').any { it == "rro" || it == "overlay" }

        val dump = dumpsysPackageOutput?.lowercase().orEmpty()
        if (dump.isBlank()) return nameSuggestsOverlay

        // Path-based signals.
        val hasOverlayPath = dump.contains("codepath=/product/overlay/") ||
            dump.contains("codepath=/system/overlay/") ||
            dump.contains("codepath=/vendor/overlay/") ||
            dump.contains("codepath=/odm/overlay/") ||
            dump.contains("codepath=/overlay/") ||
            dump.contains("resourcepath=/product/overlay/") ||
            dump.contains("resourcepath=/system/overlay/") ||
            dump.contains("resourcepath=/vendor/overlay/") ||
            dump.contains("resourcepath=/odm/overlay/") ||
            dump.contains("resourcepath=/overlay/")

        // Overlay target markers are definitive.
        val hasOverlayTarget = dump.contains("overlaytarget=")

        return hasOverlayTarget || hasOverlayPath || nameSuggestsOverlay
    }
}

